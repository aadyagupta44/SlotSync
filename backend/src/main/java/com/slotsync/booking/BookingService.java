package com.slotsync.booking;

import com.slotsync.common.ApiException;
import com.slotsync.domain.Booking;
import com.slotsync.domain.BookingOrigin;
import com.slotsync.domain.BookingStatus;
import com.slotsync.domain.Customer;
import com.slotsync.domain.Resource;
import com.slotsync.events.EventTypes;
import com.slotsync.events.OutboxWriter;
import com.slotsync.events.Payloads;
import com.slotsync.repo.BookingRepository;
import com.slotsync.repo.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Direct bookings: someone picks an open slot themselves, or cancels one.
 *
 * <p>Note what is <b>not</b> here: no "check if the slot is free, then insert"
 * dance, no application-level lock, no synchronized block. The insert is fired
 * straight at Postgres and the {@code bookings_no_overlap} exclusion constraint
 * decides the winner. Under 500 concurrent requests for the same slot exactly
 * one commits and the other 499 get a clean 409 - and it stays true no matter
 * how many backend replicas are running, because the guarantee lives in the
 * database, not in this process.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final AvailabilityService availabilityService;
    private final OutboxWriter outbox;

    public BookingService(BookingRepository bookingRepository,
                          CustomerRepository customerRepository,
                          AvailabilityService availabilityService,
                          OutboxWriter outbox) {
        this.bookingRepository = bookingRepository;
        this.customerRepository = customerRepository;
        this.availabilityService = availabilityService;
        this.outbox = outbox;
    }

    @Transactional
    public Booking book(UUID tenantId, UUID resourceId, UUID customerId, Instant startsAt) {
        Resource resource = availabilityService.requireResource(resourceId, tenantId);
        Customer customer = customerRepository.findByIdAndTenantId(customerId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Customer"));
        Instant endsAt = availabilityService.slotEndFor(resource, startsAt);

        Booking booking = new Booking();
        booking.setTenantId(tenantId);
        booking.setResourceId(resourceId);
        booking.setCustomerId(customer.getId());
        booking.setStartsAt(startsAt);
        booking.setEndsAt(endsAt);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setOrigin(BookingOrigin.DIRECT);
        booking.setConfirmedAt(Instant.now());

        // saveAndFlush, not save: we want the INSERT (and therefore any
        // constraint violation) to happen here, inside this method, so the
        // exception handler can turn it into 409 SLOT_TAKEN instead of it
        // surfacing later during an opaque commit.
        bookingRepository.saveAndFlush(booking);

        outbox.append(tenantId, "booking", booking.getId(), resourceId,
                EventTypes.BOOKING_CREATED,
                new Payloads.BookingChanged(tenantId, booking.getId(), resourceId,
                        customer.getId(), startsAt, endsAt, "CONFIRMED", "DIRECT"));

        log.info("Booked {} on {} at {}", booking.getId(), resourceId, startsAt);
        return booking;
    }

    /**
     * Cancel a confirmed booking. This is the event that starts the whole
     * waitlist engine.
     *
     * <p>Two writes happen in one transaction: the booking becomes CANCELLED,
     * and a {@code slot.freed} event goes into the outbox. Because they commit
     * together, it is impossible to end up with a cancelled slot that nobody is
     * offered, or an offer for a slot that was never actually cancelled.
     */
    @Transactional
    public Booking cancel(UUID tenantId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndTenantId(bookingId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Booking"));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return booking;   // idempotent: cancelling twice is not an error
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("NOT_CANCELLABLE",
                    "Only confirmed bookings can be cancelled (this one is " + booking.getStatus() + ")");
        }

        // Truncated to microseconds because that is all Postgres timestamptz
        // stores. Java's Instant.now() has nanosecond precision, so an
        // untruncated value would come back from the database slightly
        // different from the one we put in the event payload - and freedAt is
        // compared for equality when working out who has already been offered
        // this exact opening.
        Instant freedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(freedAt);
        // @Version on Booking means a second concurrent cancel of the same row
        // fails with an optimistic lock error rather than emitting a second
        // slot.freed event for the same opening.
        bookingRepository.saveAndFlush(booking);

        outbox.append(tenantId, "booking", booking.getId(), booking.getResourceId(),
                EventTypes.BOOKING_CANCELLED,
                new Payloads.BookingChanged(tenantId, booking.getId(), booking.getResourceId(),
                        booking.getCustomerId(), booking.getStartsAt(), booking.getEndsAt(),
                        "CANCELLED", booking.getOrigin().name()));

        outbox.append(tenantId, "booking", booking.getId(), booking.getResourceId(),
                EventTypes.SLOT_FREED,
                new Payloads.SlotFreed(tenantId, booking.getResourceId(),
                        booking.getStartsAt(), booking.getEndsAt(),
                        freedAt, booking.getId(), 0));

        log.info("Cancelled {} - slot {} freed", booking.getId(), booking.getStartsAt());
        return booking;
    }

    @Transactional(readOnly = true)
    public Booking get(UUID tenantId, UUID bookingId) {
        return bookingRepository.findByIdAndTenantId(bookingId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Booking"));
    }

    @Transactional(readOnly = true)
    public List<Booking> list(UUID tenantId, UUID resourceId) {
        return resourceId == null
                ? bookingRepository.findByTenantIdOrderByStartsAtDesc(tenantId)
                : bookingRepository.findByTenantIdAndResourceIdOrderByStartsAtDesc(tenantId, resourceId);
    }
}
