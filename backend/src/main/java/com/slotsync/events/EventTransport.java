package com.slotsync.events;

/**
 * How an event leaves this process.
 *
 * <p>A port, so the rest of the system never imports Kafka classes. Production
 * uses {@link KafkaEventTransport}; integration tests use
 * {@link InMemoryEventTransport}, which keeps the same asynchronous,
 * separate-transaction behaviour without needing a broker container.
 */
public interface EventTransport {

    /**
     * Hand the event off. Must throw if delivery could not be confirmed - the
     * outbox relay relies on the exception to schedule a retry.
     */
    void publish(EventEnvelope envelope);
}
