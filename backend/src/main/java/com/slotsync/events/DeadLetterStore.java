package com.slotsync.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Last stop for events that failed every retry.
 *
 * <p>The rule is: nothing disappears silently. If the relay cannot publish an
 * event after N attempts, or the consumer keeps throwing, the payload and the
 * error land here so a human can look at it and replay it.
 */
@Component
public class DeadLetterStore {

    private final JdbcTemplate jdbc;

    public DeadLetterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID eventId, String source, String eventType, String payload, String error) {
        jdbc.update("""
                INSERT INTO dead_letter_events (event_id, source, event_type, payload, error)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """, eventId, source, eventType, payload, truncate(error));
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM dead_letter_events", Long.class);
        return n == null ? 0 : n;
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 4000 ? error.substring(0, 4000) : error;
    }
}
