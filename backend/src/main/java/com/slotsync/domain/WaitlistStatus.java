package com.slotsync.domain;

/** Lifecycle of a waitlist entry. */
public enum WaitlistStatus {
    /** In the queue, eligible to be offered a slot. */
    WAITING,
    /** Currently holding a live offer - not eligible for a second one. */
    OFFERED,
    /** Claimed an offer and now has a real booking. */
    CONVERTED,
    /** Removed by the customer. */
    WITHDRAWN,
    /** Ignored too many offers; dropped out of the queue. */
    EXHAUSTED
}
