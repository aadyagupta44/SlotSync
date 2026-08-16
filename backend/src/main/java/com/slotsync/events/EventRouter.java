package com.slotsync.events;

import com.slotsync.stream.StreamBroadcaster;
import org.springframework.stereotype.Component;

/**
 * The single entry point for an incoming event, whichever transport delivered
 * it.
 *
 * <p>Order matters here:
 * <ol>
 *   <li>{@link EventDispatcher#dispatch} runs the business reaction inside a
 *       transaction (and de-duplicates);</li>
 *   <li>only after that transaction has committed do we push the event to
 *       connected browsers. Broadcasting first would let the UI show something
 *       that a rollback then un-does.</li>
 * </ol>
 */
@Component
public class EventRouter {

    private final EventDispatcher dispatcher;
    private final StreamBroadcaster broadcaster;

    public EventRouter(EventDispatcher dispatcher, StreamBroadcaster broadcaster) {
        this.dispatcher = dispatcher;
        this.broadcaster = broadcaster;
    }

    public void route(EventEnvelope envelope) {
        dispatcher.dispatch(envelope);
        broadcaster.broadcast(envelope);
    }
}
