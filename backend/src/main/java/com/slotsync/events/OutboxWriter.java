package com.slotsync.events;

import com.slotsync.common.JsonCodec;
import com.slotsync.domain.OutboxEvent;
import com.slotsync.repo.OutboxEventRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appends an event to the outbox <b>inside the caller's transaction</b>.
 *
 * <p>This is the entire trick behind the transactional outbox pattern. Compare:
 *
 * <pre>
 *   BAD:   save(booking); kafka.send(event);
 *          - if the send fails, the DB says cancelled but nobody is told
 *          - if the tx rolls back after the send, everyone is told about a
 *            cancellation that never happened
 *
 *   GOOD:  save(booking); outbox.append(event);   // one transaction, atomic
 *          ... later, a relay moves outbox rows to Kafka and retries forever
 * </pre>
 *
 * The database is the source of truth for "did this happen", and the broker
 * catches up.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final JsonCodec json;

    public OutboxWriter(OutboxEventRepository repository, JsonCodec json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * @param partitionKey Kafka message key. Always the resource id here, so
     *                     every event about one resource is ordered relative to
     *                     the others.
     */
    public UUID append(UUID tenantId,
                       String aggregateType,
                       UUID aggregateId,
                       UUID partitionKey,
                       String eventType,
                       Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPartitionKey(partitionKey.toString());
        event.setEventType(eventType);
        event.setPayload(json.write(payload));
        repository.save(event);
        return event.getEventId();
    }
}
