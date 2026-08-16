package com.slotsync.events;

/** The vocabulary of the system. Strings, so old events stay readable forever. */
public final class EventTypes {

    /** A slot became available (cancellation, or an offer that expired). */
    public static final String SLOT_FREED = "slot.freed";

    /** A freed slot was offered to one waitlisted customer. */
    public static final String OFFER_CREATED = "offer.created";

    /** Nobody claimed in time; the slot is about to cascade to the next person. */
    public static final String OFFER_EXPIRED = "offer.expired";

    /** A waitlisted customer accepted, turning the hold into a real booking. */
    public static final String OFFER_CLAIMED = "offer.claimed";

    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";

    /** A freed slot ran out of eligible candidates. Nobody wanted it. */
    public static final String WAITLIST_EXHAUSTED = "waitlist.exhausted";

    private EventTypes() { }
}
