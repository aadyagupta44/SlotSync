package com.slotsync.events;

import java.time.Instant;
import java.util.UUID;

/** Event bodies. Grouped in one file because they are read together. */
public final class Payloads {

    private Payloads() { }

    /**
     * "This exact time range on this resource is now free."
     *
     * <p>{@code freedAt} is the moment the slot originally opened up. It is
     * copied forward through every cascade step, which is how the dashboard can
     * say "refilled 41 seconds after the cancellation" even when three people
     * were offered it first.
     */
    public record SlotFreed(
            UUID tenantId,
            UUID resourceId,
            Instant startsAt,
            Instant endsAt,
            Instant freedAt,
            UUID sourceBookingId,
            int cascadeDepth) { }

    /** "We offered that slot to this person; they have until expiresAt." */
    public record OfferChanged(
            UUID tenantId,
            UUID offerId,
            UUID resourceId,
            UUID waitlistEntryId,
            UUID customerId,
            UUID bookingId,
            Instant startsAt,
            Instant endsAt,
            Instant expiresAt,
            Instant freedAt,
            int attempt) { }

    /** Booking lifecycle notifications. */
    public record BookingChanged(
            UUID tenantId,
            UUID bookingId,
            UUID resourceId,
            UUID customerId,
            Instant startsAt,
            Instant endsAt,
            String status,
            String origin) { }
}
