package com.slotsync.stream;

import com.slotsync.common.JsonCodec;
import com.slotsync.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans a domain event out to every backend replica over Redis pub/sub.
 *
 * <p>Why this exists: a browser's SSE connection is held by <em>one</em>
 * replica. If the event that concerns that browser is processed by a different
 * replica, the user sees nothing. Publishing to a Redis channel that all
 * replicas subscribe to solves it - whoever holds the connection delivers it.
 *
 * <p>Failures here are logged and swallowed. A live UI update is a nice-to-have;
 * it must never roll back or fail the business operation that produced it.
 */
@Component
public class StreamBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StreamBroadcaster.class);

    public static final String CHANNEL = "slotsync:stream";

    private final StringRedisTemplate redis;
    private final JsonCodec json;

    public StreamBroadcaster(StringRedisTemplate redis, JsonCodec json) {
        this.redis = redis;
        this.json = json;
    }

    public void broadcast(EventEnvelope envelope) {
        try {
            redis.convertAndSend(CHANNEL, json.write(envelope));
        } catch (Exception e) {
            log.warn("Could not broadcast {} to the live stream: {}",
                    envelope.eventType(), e.getMessage());
        }
    }
}
