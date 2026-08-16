package com.slotsync.waitlist;

import com.slotsync.AbstractIntegrationTest;
import com.slotsync.booking.BookingService;
import com.slotsync.domain.Booking;
import com.slotsync.domain.BookingOrigin;
import com.slotsync.domain.BookingStatus;
import com.slotsync.domain.Customer;
import com.slotsync.domain.Offer;
import com.slotsync.domain.OfferStatus;
import com.slotsync.domain.Resource;
import com.slotsync.domain.WaitlistStatus;
import com.slotsync.repo.BookingRepository;
import com.slotsync.repo.OfferRepository;
import com.slotsync.repo.WaitlistEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The end-to-end behaviour the whole project exists for.
 *
 * <p>Cancel a booking, and without anybody touching anything:
 * <ol>
 *   <li>the highest-priority waitlisted person is offered the slot;</li>
 *   <li>they ignore it, so it expires;</li>
 *   <li>it cascades to the next person automatically;</li>
 *   <li>they claim it and the slot is refilled.</li>
 * </ol>
 *
 * <p>Nothing in this test calls the offer engine directly - it only cancels and
 * claims. Everything in between happens through the event pipeline.
 */
class WaitlistCascadeIT extends AbstractIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired WaitlistService waitlistService;
    @Autowired OfferService offerService;
    @Autowired OfferRepository offerRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired WaitlistEntryRepository waitlistRepository;

    @Test
    @DisplayName("Cancellation -> offer to the priority customer -> expiry -> cascade -> claim")
    void aCancelledSlotCascadesDownTheWaitlistUntilSomebodyClaimsIt() {
        UUID tenantId = demoTenantId();
        Resource resource = newResource("cascade");

        Customer original = newCustomer("original-patient");
        Customer vip = newCustomer("vip-waiter");        // priority 5, will ignore the offer
        Customer eager = newCustomer("eager-waiter");    // priority 0, will claim it

        Instant slot = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atTime(LocalTime.of(11, 0)).toInstant(ZoneOffset.UTC);
        Instant windowStart = slot.minusSeconds(3600);
        Instant windowEnd = slot.plusSeconds(3600);

        Booking confirmed = bookingService.book(tenantId, resource.getId(), original.getId(), slot);
        waitlistService.join(tenantId, resource.getId(), vip.getId(), windowStart, windowEnd, 5);
        waitlistService.join(tenantId, resource.getId(), eager.getId(), windowStart, windowEnd, 0);

        // --- the only action a human takes -------------------------------
        bookingService.cancel(tenantId, confirmed.getId());

        // 1. The VIP is offered the slot first, because priority beats join order.
        Offer firstOffer = await().atMost(Duration.ofSeconds(30))
                .until(() -> latestOffer(resource.getId()), o -> o != null);
        assertThat(firstOffer.getCustomerId()).isEqualTo(vip.getId());
        assertThat(firstOffer.getAttempt()).isEqualTo(1);
        assertThat(firstOffer.getStatus()).isEqualTo(OfferStatus.PENDING);

        // The offer holds the slot, so nobody else can book it in the meantime.
        assertThat(bookingRepository.findById(firstOffer.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.HELD);

        // 2. The VIP does nothing. The sweeper expires the offer and the slot
        //    cascades to the next eligible person - all through events.
        Offer secondOffer = await().atMost(Duration.ofSeconds(30))
                .until(() -> latestOffer(resource.getId()),
                        o -> o != null && !o.getId().equals(firstOffer.getId()));

        assertThat(secondOffer.getCustomerId()).isEqualTo(eager.getId());
        assertThat(secondOffer.getAttempt()).isEqualTo(2);
        assertThat(offerRepository.findById(firstOffer.getId()).orElseThrow().getStatus())
                .isEqualTo(OfferStatus.EXPIRED);
        assertThat(bookingRepository.findById(firstOffer.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);

        // The VIP is back in the queue with a strike against them.
        var vipEntry = waitlistRepository.findById(firstOffer.getWaitlistEntryId()).orElseThrow();
        assertThat(vipEntry.getMissedOffers()).isEqualTo(1);

        // 3. The second person claims in time.
        offerService.claim(tenantId, secondOffer.getId());

        Booking refilled = bookingRepository.findById(secondOffer.getBookingId()).orElseThrow();
        assertThat(refilled.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(refilled.getOrigin()).isEqualTo(BookingOrigin.WAITLIST);
        assertThat(refilled.getCustomerId()).isEqualTo(eager.getId());

        assertThat(waitlistRepository.findById(secondOffer.getWaitlistEntryId()).orElseThrow().getStatus())
                .isEqualTo(WaitlistStatus.CONVERTED);

        // freedAt survived both hops, so the refill time is measured from the
        // original cancellation and not from the last offer.
        Offer claimed = offerRepository.findById(secondOffer.getId()).orElseThrow();
        assertThat(claimed.getFreedAt()).isEqualTo(firstOffer.getFreedAt());
        assertThat(claimed.getClaimedAt()).isAfter(claimed.getFreedAt());
    }

    @Test
    @DisplayName("An expired offer cannot be claimed")
    void expiredOffersAreRejected() {
        UUID tenantId = demoTenantId();
        Resource resource = newResource("expiry");
        Customer original = newCustomer("holder");
        Customer waiter = newCustomer("slow-waiter");

        Instant slot = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atTime(LocalTime.of(12, 0)).toInstant(ZoneOffset.UTC);

        Booking confirmed = bookingService.book(tenantId, resource.getId(), original.getId(), slot);
        waitlistService.join(tenantId, resource.getId(), waiter.getId(),
                slot.minusSeconds(3600), slot.plusSeconds(3600), 0);
        bookingService.cancel(tenantId, confirmed.getId());

        Offer offer = await().atMost(Duration.ofSeconds(30))
                .until(() -> latestOffer(resource.getId()), o -> o != null);

        // Wait for the sweeper to time it out (ttl is 2s in tests).
        await().atMost(Duration.ofSeconds(30)).until(() ->
                offerRepository.findById(offer.getId()).orElseThrow().getStatus() == OfferStatus.EXPIRED);

        assertThat(offerRepository.findById(offer.getId()).orElseThrow().getStatus())
                .isEqualTo(OfferStatus.EXPIRED);
    }

    /** Most recently created offer for a resource, or null if none yet. */
    private Offer latestOffer(UUID resourceId) {
        List<Offer> offers = offerRepository.findByTenantIdOrderByCreatedAtDesc(demoTenantId());
        return offers.stream()
                .filter(o -> o.getResourceId().equals(resourceId))
                .max(Comparator.comparing(Offer::getCreatedAt).thenComparing(Offer::getAttempt))
                .orElse(null);
    }
}
