package com.slotsync.events;

import com.slotsync.config.SlotSyncProperties;
import com.slotsync.domain.OutboxEvent;
import com.slotsync.repo.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Moves outbox rows to the message broker.
 *
 * <p>Runs on <b>every</b> backend replica at once, which is safe because the
 * claim query uses {@code FOR UPDATE SKIP LOCKED}: an event locked by replica A
 * is simply invisible to replica B, so no event is ever published twice and no
 * replica sits blocked waiting for a lock.
 *
 * <p>Failure handling is exponential backoff on the row itself
 * (2s, 4s, 8s, ... capped), and after {@code max-attempts} the event is marked
 * FAILED and copied to the dead-letter table.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final OutboxEventRepository repository;
    private final EventTransport transport;
    private final DeadLetterStore deadLetters;
    private final SlotSyncProperties properties;

    public OutboxRelay(OutboxEventRepository repository,
                       EventTransport transport,
                       DeadLetterStore deadLetters,
                       SlotSyncProperties properties) {
        this.repository = repository;
        this.transport = transport;
        this.deadLetters = deadLetters;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${slotsync.outbox.interval-ms}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.lockDueBatch(properties.outbox().batchSize());
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            transport.publish(new EventEnvelope(
                    event.getEventId(),
                    event.getTenantId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getPartitionKey(),
                    event.getPayload(),
                    event.getCreatedAt()));

            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
        } catch (Exception e) {
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            event.setLastError(e.getMessage());

            if (attempts >= properties.outbox().maxAttempts()) {
                event.setStatus("FAILED");
                deadLetters.record(event.getEventId(), "outbox-relay",
                        event.getEventType(), event.getPayload(), e.toString());
                log.error("Outbox event {} dead-lettered after {} attempts",
                        event.getEventId(), attempts, e);
            } else {
                event.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
                log.warn("Outbox event {} failed (attempt {}), retrying later: {}",
                        event.getEventId(), attempts, e.getMessage());
            }
        }
        // No explicit save(): these are managed JPA entities inside the
        // transaction, so the changes are flushed on commit.
    }

    /** 2s, 4s, 8s, 16s ... capped at five minutes. */
    private Duration backoff(int attempts) {
        Duration d = Duration.ofSeconds((long) Math.pow(2, Math.min(attempts, 8)));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
