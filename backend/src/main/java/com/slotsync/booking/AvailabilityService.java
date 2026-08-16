package com.slotsync.booking;

import com.slotsync.common.ApiException;
import com.slotsync.domain.Booking;
import com.slotsync.domain.BookingStatus;
import com.slotsync.domain.Resource;
import com.slotsync.repo.BookingRepository;
import com.slotsync.repo.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out what a day looks like for one resource.
 *
 * <p>Slots are computed from opening hours + slot length, never stored. A
 * resource row therefore describes every day forever, and changing the opening
 * hours needs no migration of pre-generated rows.
 */
@Service
public class AvailabilityService {

    /** The statuses that make a slot unavailable. */
    private static final List<BookingStatus> OCCUPYING =
            List.of(BookingStatus.HELD, BookingStatus.CONFIRMED);

    private final ResourceRepository resourceRepository;
    private final BookingRepository bookingRepository;

    public AvailabilityService(ResourceRepository resourceRepository,
                               BookingRepository bookingRepository) {
        this.resourceRepository = resourceRepository;
        this.bookingRepository = bookingRepository;
    }

    /** One row of the availability grid. */
    public record SlotView(Instant startsAt,
                           Instant endsAt,
                           String status,          // OPEN | HELD | BOOKED
                           UUID bookingId,
                           UUID customerId) { }

    public Resource requireResource(UUID resourceId, UUID tenantId) {
        return resourceRepository.findByIdAndTenantId(resourceId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Resource"));
    }

    @Transactional(readOnly = true)
    public List<SlotView> availability(UUID tenantId, UUID resourceId, LocalDate date) {
        Resource resource = requireResource(resourceId, tenantId);
        ZoneId zone = ZoneId.of(resource.getTimezone());

        Instant dayStart = date.atTime(resource.getOpeningTime()).atZone(zone).toInstant();
        Instant dayEnd = date.atTime(resource.getClosingTime()).atZone(zone).toInstant();

        // One query for the whole day, then match in memory. Avoids N queries
        // for N slots.
        Map<Instant, Booking> byStart = bookingRepository
                .findOverlapping(resourceId, dayStart, dayEnd, OCCUPYING)
                .stream()
                .collect(Collectors.toMap(Booking::getStartsAt, Function.identity(), (a, b) -> a));

        List<SlotView> slots = new ArrayList<>();
        Duration length = Duration.ofMinutes(resource.getSlotMinutes());
        for (Instant cursor = dayStart; !cursor.plus(length).isAfter(dayEnd); cursor = cursor.plus(length)) {
            Booking booking = byStart.get(cursor);
            if (booking == null) {
                slots.add(new SlotView(cursor, cursor.plus(length), "OPEN", null, null));
            } else {
                String status = booking.getStatus() == BookingStatus.HELD ? "HELD" : "BOOKED";
                slots.add(new SlotView(cursor, cursor.plus(length), status,
                        booking.getId(), booking.getCustomerId()));
            }
        }
        return slots;
    }

    /**
     * Check that a requested start time is a real slot boundary for this
     * resource, and return where the slot ends.
     *
     * <p>Without this, a client could book 10:07-10:37 and quietly poison every
     * later slot on that day, because overlapping ranges would block them.
     */
    public Instant slotEndFor(Resource resource, Instant startsAt) {
        ZoneId zone = ZoneId.of(resource.getTimezone());
        LocalDate date = startsAt.atZone(zone).toLocalDate();
        LocalTime localStart = startsAt.atZone(zone).toLocalTime();

        Duration length = Duration.ofMinutes(resource.getSlotMinutes());
        Instant dayStart = date.atTime(resource.getOpeningTime()).atZone(zone).toInstant();
        Instant dayEnd = date.atTime(resource.getClosingTime()).atZone(zone).toInstant();

        if (startsAt.isBefore(dayStart) || !startsAt.plus(length).isBefore(dayEnd.plusNanos(1))) {
            throw ApiException.badRequest("OUTSIDE_OPENING_HOURS",
                    "Requested time is outside " + resource.getOpeningTime()
                            + "-" + resource.getClosingTime() + " for this resource");
        }

        long minutesFromOpen = Duration.between(dayStart, startsAt).toMinutes();
        if (minutesFromOpen % resource.getSlotMinutes() != 0) {
            throw ApiException.badRequest("NOT_A_SLOT_BOUNDARY",
                    "Bookings must start on a " + resource.getSlotMinutes()
                            + "-minute boundary; got " + localStart);
        }
        return startsAt.plus(length);
    }
}
