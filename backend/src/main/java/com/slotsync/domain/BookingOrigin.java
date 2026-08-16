package com.slotsync.domain;

/** Where a booking came from. Drives the "auto-refill rate" metric. */
public enum BookingOrigin {
    /** Somebody booked an open slot themselves. */
    DIRECT,
    /** The waitlist engine created it after a cancellation. */
    WAITLIST
}
