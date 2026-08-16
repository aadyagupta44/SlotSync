package com.slotsync.waitlist;

import com.slotsync.common.ApiException;
import com.slotsync.config.SlotSyncProperties;
import com.slotsync.domain.Booking;
import com.slotsync.domain.BookingOrigin;
import com.slotsync.domain.BookingStatus;
import com.slotsync.domain.Offer;
import com.slotsync.domain.OfferStatus;
import com.slotsync.domain.WaitlistEntry;
import com.slotsync.domain.WaitlistStatus;
import com.slotsync.events.EventTypes;
import com.slotsync.events.OutboxWriter;
import com.slotsync.events.Payloads;
import com.slotsync.repo.BookingRepository;
import com.slotsync.repo.OfferRepository;
import com.slotsync.repo.WaitlistEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The engine. Everything interesting about this project happens in this class.
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@link #offerToNextCandidate} - a slot is free, give it to exactly one
 *       person for a limited time;</li>
 *   <li>{@link #claim} - that person said yes, turn the hold into a booking;</li>
 *   <li>{@link #expire} - that person did not answer, release the hold and
 *       re-publish the slot so it cascades to the next person.</li>
 * </ul>
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private static final List<BookingStatus> OCCUPYING =
            List.of(BookingStatus.HELD, BookingStatus.CONFIRMED);

    private final WaitlistEntryRepository waitlistRepository;
    private final OfferRepository offerRepository;
    private final BookingRepository bookingRepository;
    private final OutboxWriter outbox;
    private final SlotSyncProperties properties;

    public OfferService(WaitlistEntryRepository waitlistRepository,
                        OfferRepository offerRepository,
                        BookingRepository bookingRepository,
                        OutboxWriter outbox,
                        SlotSyncProperties properties) {
        this.waitlistRepository = waitlistRepository;
        this.offerRepository = offerRepository;
        this.bookingRepository = bookingRepository;
        this.outbox = outbox;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 1. Offer a freed slot to the next candidate
    // ------------------------------------------------------------------

    /**
     * Reacts to a {@code slot.freed} event.
     *
     * <p>Steps, all inside one transaction:
     * <ol>
     *   <li>Find who has already been tried for <em>this exact opening</em>, so
     *       a cascade never loops back to someone who already ignored it.</li>
     *   <li>Lock the best remaining candidate with
     *       {@code FOR UPDATE SKIP LOCKED} - two workers processing two
     *       different freed slots can run at the same instant and will never
     *       select the same person.</li>
     *   <li>Re-check the slot is still free (somebody may have booked it
     *       directly a moment ago).</li>
     *   <li>Insert a HELD booking. This is what actually reserves the slot -
     *       and the exclusion constraint means it cannot collide with a real
     *       booking.</li>
     *   <li>Insert the offer row and emit {@code offer.created}.</li>
     * </ol>
     *
     * <p>If step 4 loses a race and the constraint fires, the exception
     * propagates, the transaction (including the "event already processed"
     * marker) rolls back, the event is redelivered, and the re-check in step 3
     * then sees the booking and exits cleanly. The retry converges - we do not
     * need to get it right first time.
     */
    @Transactional
    public void offerToNextCandidate(Payloads.SlotFreed slot) {
        List<UUID> alreadyTried = offerRepository.findTriedEntryIds(
                slot.resourceId(), slot.startsAt(), slot.endsAt(), slot.freedAt());

        Optional<WaitlistEntry> candidate = waitlistRepository.lockNextCandidate(
                slot.resourceId(), slot.startsAt(), slot.endsAt(), toPgUuidArray(alreadyTried));

        if (candidate.isEmpty()) {
            log.info("No eligible waitlist candidate left for {} at {} - slot stays open",
                    slot.resourceId(), slot.startsAt());
            outbox.append(slot.tenantId(), "offer", slot.sourceBookingId(), slot.resourceId(),
                    EventTypes.WAITLIST_EXHAUSTED, slot);
            return;
        }

        boolean slotTaken = !bookingRepository
                .findOverlapping(slot.resourceId(), slot.startsAt(), slot.endsAt(), OCCUPYING)
                .isEmpty();
        if (slotTaken) {
            log.info("Slot {} on {} was taken before we could offer it", slot.startsAt(), slot.resourceId());
            return;
        }

        WaitlistEntry entry = candidate.get();
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(properties.offer().ttlSeconds()));

        Booking hold = new Booking();
        hold.setTenantId(slot.tenantId());
        hold.setResourceId(slot.resourceId());
        hold.setCustomerId(entry.getCustomerId());
        hold.setStartsAt(slot.startsAt());
        hold.setEndsAt(slot.endsAt());
        hold.setStatus(BookingStatus.HELD);
        hold.setOrigin(BookingOrigin.WAITLIST);
        hold.setHoldExpiresAt(expiresAt);
        bookingRepository.saveAndFlush(hold);

        Offer offer = new Offer();
        offer.setTenantId(slot.tenantId());
        offer.setResourceId(slot.resourceId());
        offer.setWaitlistEntryId(entry.getId());
        offer.setCustomerId(entry.getCustomerId());
        offer.setBookingId(hold.getId());
        offer.setStartsAt(slot.startsAt());
        offer.setEndsAt(slot.endsAt());
        offer.setStatus(OfferStatus.PENDING);
        offer.setAttempt(alreadyTried.size() + 1);
        offer.setFreedAt(slot.freedAt());
        offer.setExpiresAt(expiresAt);
        offerRepository.saveAndFlush(offer);

        entry.setStatus(WaitlistStatus.OFFERED);
        waitlistRepository.save(entry);

        outbox.append(slot.tenantId(), "offer", offer.getId(), slot.resourceId(),
                EventTypes.OFFER_CREATED, describe(offer));

        log.info("Offered slot {} on {} to customer {} (attempt {}), expires {}",
                slot.startsAt(), slot.resourceId(), entry.getCustomerId(),
                offer.getAttempt(), expiresAt);
    }

    // ------------------------------------------------------------------
    // 2. The customer accepts
    // ------------------------------------------------------------------

    /**
     * Turn a pending offer into a confirmed booking.
     *
     * <p>The row lock taken by {@code lockById} is what makes a double-click
     * safe: the second request waits, then sees status CLAIMED and gets a 409.
     * It also excludes the sweeper, so an offer cannot be expired out from
     * under a claim that is halfway through.
     *
     * <p>The deadline is re-checked against the database clock, not the
     * browser's. A client with a slow clock cannot claim a stale offer.
     */
    @Transactional
    public Offer claim(UUID tenantId, UUID offerId) {
        Offer offer = offerRepository.findByIdAndTenantId(offerId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Offer"));

        offer = offerRepository.lockById(offer.getId())
                .orElseThrow(() -> ApiException.notFound("Offer"));

        if (offer.getStatus() == OfferStatus.CLAIMED) {
            return offer;   // idempotent: claiming twice returns the same result
        }
        if (offer.getStatus() != OfferStatus.PENDING) {
            throw ApiException.conflict("OFFER_NOT_PENDING",
                    "This offer is " + offer.getStatus() + " and can no longer be claimed");
        }
        if (offer.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("OFFER_EXPIRED",
                    "This offer expired at " + offer.getExpiresAt());
        }

        Booking hold = bookingRepository.findById(offer.getBookingId())
                .orElseThrow(() -> ApiException.notFound("Held booking"));
        if (hold.getStatus() != BookingStatus.HELD) {
            throw ApiException.conflict("HOLD_GONE",
                    "The hold for this offer is no longer active (" + hold.getStatus() + ")");
        }

        Instant now = Instant.now();
        hold.setStatus(BookingStatus.CONFIRMED);
        hold.setConfirmedAt(now);
        hold.setHoldExpiresAt(null);
        bookingRepository.save(hold);

        offer.setStatus(OfferStatus.CLAIMED);
        offer.setClaimedAt(now);
        offerRepository.save(offer);

        WaitlistEntry entry = waitlistRepository.findById(offer.getWaitlistEntryId())
                .orElseThrow(() -> ApiException.notFound("Waitlist entry"));
        entry.setStatus(WaitlistStatus.CONVERTED);
        waitlistRepository.save(entry);

        outbox.append(tenantId, "offer", offer.getId(), offer.getResourceId(),
                EventTypes.OFFER_CLAIMED, describe(offer));
        outbox.append(tenantId, "booking", hold.getId(), offer.getResourceId(),
                EventTypes.BOOKING_CONFIRMED,
                new Payloads.BookingChanged(tenantId, hold.getId(), hold.getResourceId(),
                        hold.getCustomerId(), hold.getStartsAt(), hold.getEndsAt(),
                        "CONFIRMED", "WAITLIST"));

        log.info("Offer {} claimed by {} - refilled {}s after the slot was freed",
                offer.getId(), hold.getCustomerId(),
                Duration.between(offer.getFreedAt(), now).toSeconds());
        return offer;
    }

    // ------------------------------------------------------------------
    // 3. Nobody answered - cascade
    // ------------------------------------------------------------------

    /**
     * Expire one offer and hand the slot on.
     *
     * <p>Called by the sweeper for rows it has already locked. The final
     * {@code slot.freed} event is the cascade: it re-enters
     * {@link #offerToNextCandidate} through the message pipeline, where the
     * exclusion list now also contains the person who just timed out.
     *
     * <p>Because the cascade is a message and not a recursive call, it survives
     * a process restart, can be picked up by a different replica, and each hop
     * is independently retryable.
     */
    void expire(Offer offer) {
        if (offer.getStatus() != OfferStatus.PENDING) {
            return;
        }
        offer.setStatus(OfferStatus.EXPIRED);
        offerRepository.save(offer);

        bookingRepository.findById(offer.getBookingId()).ifPresent(hold -> {
            if (hold.getStatus() == BookingStatus.HELD) {
                hold.setStatus(BookingStatus.EXPIRED);   // drops out of the overlap constraint
                hold.setHoldExpiresAt(null);
                bookingRepository.save(hold);
            }
        });

        Optional<WaitlistEntry> maybeEntry = waitlistRepository.findById(offer.getWaitlistEntryId());
        if (maybeEntry.isPresent()) {
            WaitlistEntry entry = maybeEntry.get();
            entry.setMissedOffers(entry.getMissedOffers() + 1);
            // Ignore too many offers and you drop out of the queue. Otherwise
            // you go back in - just behind everyone who does respond, because
            // the queue orders by missed_offers ascending.
            entry.setStatus(entry.getMissedOffers() >= properties.offer().maxMissedOffers()
                    ? WaitlistStatus.EXHAUSTED
                    : WaitlistStatus.WAITING);
            waitlistRepository.save(entry);
        }

        outbox.append(offer.getTenantId(), "offer", offer.getId(), offer.getResourceId(),
                EventTypes.OFFER_EXPIRED, describe(offer));

        outbox.append(offer.getTenantId(), "offer", offer.getId(), offer.getResourceId(),
                EventTypes.SLOT_FREED,
                new Payloads.SlotFreed(offer.getTenantId(), offer.getResourceId(),
                        offer.getStartsAt(), offer.getEndsAt(),
                        offer.getFreedAt(),          // preserved from the original cancellation
                        offer.getBookingId(),
                        offer.getAttempt()));

        log.info("Offer {} expired (attempt {}) - cascading to the next candidate",
                offer.getId(), offer.getAttempt());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Payloads.OfferChanged describe(Offer offer) {
        return new Payloads.OfferChanged(
                offer.getTenantId(), offer.getId(), offer.getResourceId(),
                offer.getWaitlistEntryId(), offer.getCustomerId(), offer.getBookingId(),
                offer.getStartsAt(), offer.getEndsAt(), offer.getExpiresAt(),
                offer.getFreedAt(), offer.getAttempt());
    }

    /**
     * Postgres array literal, e.g. {@code {a1b2...,c3d4...}} or {@code {}}.
     * Passing it as one parameter keeps the query a plain prepared statement -
     * no string-built SQL, and an empty list is handled without a special case
     * ({@code x <> ALL('{}')} is simply true).
     */
    private String toPgUuidArray(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
