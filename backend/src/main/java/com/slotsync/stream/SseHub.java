package com.slotsync.stream;

import com.slotsync.common.JsonCodec;
import com.slotsync.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the open Server-Sent Events connections for this replica and pushes
 * events into them.
 *
 * <p>SSE rather than WebSockets because the traffic is strictly one-way
 * (server to browser) - no handshake upgrade, no extra protocol, and the
 * browser reconnects on its own if the connection drops.
 *
 * <p>Emitters are stored per tenant in a {@link ConcurrentHashMap} of
 * {@link CopyOnWriteArrayList}: many threads read the list to deliver events
 * while connections are added and removed, and copy-on-write makes iteration
 * safe without locking on the hot path.
 */
@Component
public class SseHub implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;   // 30 minutes

    private final Map<UUID, List<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();
    private final JsonCodec json;

    public SseHub(JsonCodec json) {
        this.json = json;
    }

    public SseEmitter subscribe(UUID tenantId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> emitters =
                emittersByTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        // Always clean up, whichever way the connection ends, or the list grows
        // forever and every event pays to iterate dead entries.
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** Called by Redis pub/sub when any replica publishes an event. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            EventEnvelope envelope = json.read(body, EventEnvelope.class);
            deliver(envelope);
        } catch (Exception e) {
            log.warn("Bad message on the stream channel: {}", e.getMessage());
        }
    }

    private void deliver(EventEnvelope envelope) {
        List<SseEmitter> emitters = emittersByTenant.get(envelope.tenantId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(envelope.eventType())
                        .data(json.write(envelope)));
            } catch (Exception e) {
                emitters.remove(emitter);   // browser went away
            }
        }
    }

    /**
     * Proxies and load balancers close idle connections. A comment frame every
     * 20 seconds keeps them open without the client having to reconnect.
     */
    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        emittersByTenant.forEach((tenantId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        });
    }

    public int connectionCount() {
        return emittersByTenant.values().stream().mapToInt(List::size).sum();
    }
}
