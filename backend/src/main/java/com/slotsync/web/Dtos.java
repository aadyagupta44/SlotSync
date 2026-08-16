package com.slotsync.web;

import com.slotsync.booking.AvailabilityService;
import com.slotsync.domain.Booking;
import com.slotsync.domain.Customer;
import com.slotsync.domain.Notification;
import com.slotsync.domain.Offer;
import com.slotsync.domain.Resource;
import com.slotsync.domain.WaitlistEntry;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Request and response shapes.
 *
 * <p>Kept separate from the JPA entities so the API is not accidentally coupled
 * to the database layout - a column rename should not change the JSON contract,
 * and internal fields (version numbers, tenant ids) never leak out.
 */
public final class Dtos {

    private Dtos() { }

    // ------------------------------ requests ------------------------------

    public record CreateBookingRequest(
            @NotNull UUID resourceId,
            @NotNull UUID customerId,
            @NotNull Instant startsAt) { }

    public record JoinWaitlistRequest(
            @NotNull UUID resourceId,
            @NotNull UUID customerId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @Min(0) int priority) { }

    // ------------------------------ responses -----------------------------

    public record ResourceView(UUID id, String name, String timezone,
                               LocalTime openingTime, LocalTime closingTime, int slotMinutes) {

        public static ResourceView of(Resource r) {
            return new ResourceView(r.getId(), r.getName(), r.getTimezone(),
                    r.getOpeningTime(), r.getClosingTime(), r.getSlotMinutes());
        }
    }

    public record CustomerView(UUID id, String name, String email, String phone) {

        public static CustomerView of(Customer c) {
            return new CustomerView(c.getId(), c.getName(), c.getEmail(), c.getPhone());
        }
    }

    public record SlotView(Instant startsAt, Instant endsAt, String status,
                           UUID bookingId, UUID customerId) {

        public static SlotView of(AvailabilityService.SlotView s) {
            return new SlotView(s.startsAt(), s.endsAt(), s.status(), s.bookingId(), s.customerId());
        }
    }

    public record BookingView(UUID id, UUID resourceId, UUID customerId,
                              Instant startsAt, Instant endsAt,
                              String status, String origin,
                              Instant holdExpiresAt, Instant cancelledAt, Instant confirmedAt) {

        public static BookingView of(Booking b) {
            return new BookingView(b.getId(), b.getResourceId(), b.getCustomerId(),
                    b.getStartsAt(), b.getEndsAt(),
                    b.getStatus().name(), b.getOrigin().name(),
                    b.getHoldExpiresAt(), b.getCancelledAt(), b.getConfirmedAt());
        }
    }

    public record WaitlistView(UUID id, UUID resourceId, UUID customerId,
                               Instant windowStart, Instant windowEnd,
                               int priority, int missedOffers, String status, Instant createdAt) {

        public static WaitlistView of(WaitlistEntry w) {
            return new WaitlistView(w.getId(), w.getResourceId(), w.getCustomerId(),
                    w.getWindowStart(), w.getWindowEnd(), w.getPriority(),
                    w.getMissedOffers(), w.getStatus().name(), w.getCreatedAt());
        }
    }

    public record OfferView(UUID id, UUID resourceId, UUID customerId, UUID waitlistEntryId,
                            UUID bookingId, Instant startsAt, Instant endsAt,
                            String status, int attempt,
                            Instant freedAt, Instant expiresAt, Instant claimedAt,
                            Long secondsRemaining) {

        public static OfferView of(Offer o) {
            long remaining = java.time.Duration.between(Instant.now(), o.getExpiresAt()).toSeconds();
            return new OfferView(o.getId(), o.getResourceId(), o.getCustomerId(),
                    o.getWaitlistEntryId(), o.getBookingId(),
                    o.getStartsAt(), o.getEndsAt(), o.getStatus().name(), o.getAttempt(),
                    o.getFreedAt(), o.getExpiresAt(), o.getClaimedAt(),
                    Math.max(remaining, 0));
        }
    }

    public record NotificationView(UUID id, UUID customerId, String channel,
                                   String subject, String body, UUID offerId, Instant createdAt) {

        public static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getCustomerId(), n.getChannel(),
                    n.getSubject(), n.getBody(), n.getOfferId(), n.getCreatedAt());
        }
    }
}
