package com.slotsync.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test transport: an in-process queue drained by a scheduled loop.
 *
 * <p>It deliberately keeps the two properties that make the Kafka path
 * interesting, so tests exercise real behaviour rather than a shortcut:
 *
 * <ul>
 *   <li>delivery is <b>asynchronous</b> - the producer's transaction commits
 *       before the consumer runs;</li>
 *   <li>the consumer runs in its <b>own transaction</b>, so a consumer failure
 *       cannot roll back the producer's work.</li>
 * </ul>
 *
 * Selected with {@code slotsync.events.transport=inmemory}.
 */
@Component
@ConditionalOnProperty(name = "slotsync.events.transport", havingValue = "inmemory")
public class InMemoryEventTransport implements EventTransport {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventTransport.class);

    private final ConcurrentLinkedQueue<EventEnvelope> queue = new ConcurrentLinkedQueue<>();
    private final EventRouter router;

    public InMemoryEventTransport(EventRouter router) {
        this.router = router;
    }

    @Override
    public void publish(EventEnvelope envelope) {
        queue.add(envelope);
    }

    @Scheduled(fixedDelay = 100)
    public void drain() {
        EventEnvelope envelope;
        while ((envelope = queue.poll()) != null) {
            try {
                router.route(envelope);
            } catch (Exception e) {
                log.error("In-memory delivery failed for {}", envelope.eventId(), e);
            }
        }
    }

    /** Test helper: true when everything published so far has been handled. */
    public boolean isDrained() {
        return queue.isEmpty();
    }
}
