package com.slotsync.domain;

/** Lifecycle of one offer (one slot shown to one person for a limited time). */
public enum OfferStatus {
    /** Live - the customer can still claim it. */
    PENDING,
    /** Accepted in time; the held booking became CONFIRMED. */
    CLAIMED,
    /** Deadline passed. The sweeper cascades the slot to the next candidate. */
    EXPIRED,
    /** Voided by the system (e.g. the original customer un-cancelled). */
    CANCELLED
}
