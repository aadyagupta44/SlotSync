package com.slotsync.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumer-side de-duplication.
 *
 * <p>Kafka guarantees <em>at-least-once</em> delivery: after a rebalance or a
 * failed offset commit, the same event can be handed to the consumer again.
 * That is fine as long as handling it twice has the same effect as handling it
 * once.
 *
 * <p>This class provides that. The insert runs in the <b>same transaction</b>
 * as the handler's work, so either both happen or neither does. A redelivery
 * hits the primary key, {@code ON CONFLICT DO NOTHING} returns 0 rows, and the
 * handler is skipped. At-least-once delivery becomes exactly-once effect.
 */
@Component
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} if this is the first time {@code consumer} has seen
     *         {@code eventId} (so the caller should do the work),
     *         {@code false} if it is a duplicate.
     */
    public boolean claim(UUID eventId, String consumer) {
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, consumer)
                VALUES (?, ?)
                ON CONFLICT (event_id, consumer) DO NOTHING
                """, eventId, consumer);
        return inserted == 1;
    }
}
