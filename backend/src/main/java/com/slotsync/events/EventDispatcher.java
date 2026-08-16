package com.slotsync.events;

import com.slotsync.common.JsonCodec;
import com.slotsync.notify.NotificationService;
import com.slotsync.waitlist.OfferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an event into a reaction.
 *
 * <p>This is where the cascade actually loops: a {@code slot.freed} event makes
 * an offer, an expired offer publishes another {@code slot.freed}, which comes
 * back through here and offers the slot to the next person. The recursion lives
 * in the message flow, not in a Java loop - so it survives restarts, spreads
 * across replicas, and every step is individually retryable.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    /** Name recorded in processed_events. A second consumer would use its own. */
    private static final String CONSUMER = "core";

    private final ProcessedEventStore processedEvents;
    private final OfferService offerService;
    private final NotificationService notificationService;
    private final JsonCodec json;

    public EventDispatcher(ProcessedEventStore processedEvents,
                           OfferService offerService,
                           NotificationService notificationService,
                           JsonCodec json) {
        this.processedEvents = processedEvents;
        this.offerService = offerService;
        this.notificationService = notificationService;
        this.json = json;
    }

    @Transactional
    public void dispatch(EventEnvelope envelope) {
        // Same transaction as the work below: if the work rolls back, the
        // "already processed" marker rolls back with it and redelivery retries.
        if (!processedEvents.claim(envelope.eventId(), CONSUMER)) {
            log.debug("Duplicate delivery of {} ignored", envelope.eventId());
            return;
        }

        switch (envelope.eventType()) {
            case EventTypes.SLOT_FREED -> offerService.offerToNextCandidate(
                    json.read(envelope.payload(), Payloads.SlotFreed.class));

            case EventTypes.OFFER_CREATED -> notificationService.notifyOfferCreated(
                    json.read(envelope.payload(), Payloads.OfferChanged.class));

            case EventTypes.OFFER_EXPIRED -> notificationService.notifyOfferExpired(
                    json.read(envelope.payload(), Payloads.OfferChanged.class));

            case EventTypes.OFFER_CLAIMED -> notificationService.notifyOfferClaimed(
                    json.read(envelope.payload(), Payloads.OfferChanged.class));

            case EventTypes.BOOKING_CANCELLED,
                 EventTypes.BOOKING_CREATED,
                 EventTypes.BOOKING_CONFIRMED,
                 EventTypes.WAITLIST_EXHAUSTED -> {
                // Nothing to do server-side; these exist for the live feed and
                // for anyone who later wants to build a read model off them.
                log.debug("Observed {} for {}", envelope.eventType(), envelope.aggregateId());
            }

            default -> log.warn("Unknown event type {} - ignoring", envelope.eventType());
        }
    }
}
