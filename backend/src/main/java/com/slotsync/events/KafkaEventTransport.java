package com.slotsync.events;

import com.slotsync.common.JsonCodec;
import com.slotsync.config.SlotSyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Publishes to Kafka and <b>waits for the broker acknowledgement</b>.
 *
 * <p>The blocking {@code get()} is deliberate. The relay must not mark an
 * outbox row PUBLISHED until the broker has actually accepted the record;
 * fire-and-forget would reintroduce the exact message-loss window the outbox
 * exists to close.
 *
 * <p>Combined with {@code acks=all} and {@code enable.idempotence=true} in
 * application.yml, a producer retry cannot silently create a duplicate record.
 */
@Component
@ConditionalOnProperty(name = "slotsync.events.transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventTransport implements EventTransport {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SlotSyncProperties properties;
    private final JsonCodec json;

    public KafkaEventTransport(KafkaTemplate<String, String> kafkaTemplate,
                               SlotSyncProperties properties,
                               JsonCodec json) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void publish(EventEnvelope envelope) {
        try {
            kafkaTemplate
                    .send(properties.events().topic(), envelope.partitionKey(), json.write(envelope))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + envelope.eventId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka publish failed for " + envelope.eventId(), e);
        }
    }
}
