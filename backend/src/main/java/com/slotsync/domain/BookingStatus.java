package com.slotsync.domain;

/**
 * Lifecycle of a booking row.
 *
 * <p>HELD and CONFIRMED are the two "occupies the slot" states - they are the
 * exact set listed in the {@code bookings_no_overlap} exclusion constraint, so
 * changing this enum means changing that constraint too.
 */
public enum BookingStatus {
    /** Temporary reservation while a waitlisted customer decides. */
    HELD,
    /** A real appointment. */
    CONFIRMED,
    /** Customer cancelled - this is what frees a slot and starts the engine. */
    CANCELLED,
    /** A HELD row whose deadline passed without a claim. */
    EXPIRED
}
