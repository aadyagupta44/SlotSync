package com.slotsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable number in the system, bound from {@code application.yml}.
 * Nothing about timing or batching is hard-coded in the business classes.
 */
@ConfigurationProperties(prefix = "slotsync")
public record SlotSyncProperties(
        Offer offer,
        Sweeper sweeper,
        Outbox outbox,
        RateLimit ratelimit,
        Events events) {

    /**
     * @param ttlSeconds      how long a waitlisted customer has to claim a slot
     *                        before it cascades to the next person
     * @param maxMissedOffers how many offers one person may ignore before the
     *                        entry is dropped from the queue
     */
    public record Offer(int ttlSeconds, int maxMissedOffers) { }

    /**
     * @param intervalMs how often each instance looks for expired offers
     * @param batchSize  how many expired offers one tick claims
     */
    public record Sweeper(long intervalMs, int batchSize) { }

    /**
     * @param intervalMs  how often unpublished events are relayed to Kafka
     * @param batchSize   rows per relay tick
     * @param maxAttempts after this many failures the event is dead-lettered
     */
    public record Outbox(long intervalMs, int batchSize, int maxAttempts) { }

    /**
     * Token bucket, per tenant.
     *
     * @param capacity        burst size
     * @param refillPerSecond sustained rate
     */
    public record RateLimit(boolean enabled, int capacity, int refillPerSecond) { }

    /**
     * @param topic     main event topic
     * @param dltTopic  dead-letter topic for messages the consumer keeps failing
     * @param transport {@code kafka} in production, {@code inmemory} in tests
     */
    public record Events(String topic, String dltTopic, String transport) { }
}
