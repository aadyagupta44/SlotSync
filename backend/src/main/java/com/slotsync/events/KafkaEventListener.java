package com.slotsync.events;

import com.slotsync.common.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the main topic and hands each record to {@link EventRouter}.
 *
 * <p>Deliberately thin. Retry policy, backoff and dead-lettering are configured
 * once in {@code KafkaConfig} rather than being try/catch-ed here, so every
 * listener added later inherits the same behaviour.
 *
 * <p>If this method throws, Spring Kafka retries with backoff and finally
 * publishes the record to {@code slotsync.events.DLT}. The offset is only
 * committed once the record is either handled or dead-lettered - so a crash
 * mid-handler means redelivery, which is exactly why
 * {@link ProcessedEventStore} exists.
 */
@Component
@ConditionalOnProperty(name = "slotsync.events.transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventListener.class);

    private final EventRouter router;
    private final JsonCodec json;

    public KafkaEventListener(EventRouter router, JsonCodec json) {
        this.router = router;
        this.json = json;
    }

    @KafkaListener(topics = "${slotsync.events.topic}", groupId = "slotsync-core")
    public void onMessage(String message) {
        EventEnvelope envelope = json.read(message, EventEnvelope.class);
        log.debug("Received {} ({})", envelope.eventType(), envelope.eventId());
        router.route(envelope);
    }
}
