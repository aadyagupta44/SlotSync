package com.slotsync.events;

import java.time.Instant;
import java.util.UUID;

/**
 * What actually travels over Kafka.
 *
 * <p>The body stays a JSON {@code String} rather than a typed object on
 * purpose: producer and consumer can then be deployed independently without a
 * shared class version, and an unknown field never blows up deserialisation.
 *
 * @param eventId       stable id, used by consumers to detect duplicates
 * @param partitionKey  the resource id - all events for one resource land on
 *                      one partition and are therefore processed in order
 */
public record EventEnvelope(
        UUID eventId,
        UUID tenantId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String partitionKey,
        String payload,
        Instant occurredAt) {
}
