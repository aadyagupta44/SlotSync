package com.slotsync.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * A short-lived "only one instance should bother doing this right now" lock.
 *
 * <p><b>Read this before using it as a correctness argument.</b> A Redis lock
 * is not a distributed mutex you can bet data integrity on: if Redis fails
 * over, or a holder pauses for a long GC past the TTL, two instances can
 * believe they hold it at the same time.
 *
 * <p>So SlotSync uses it only as an <em>optimisation</em>. The sweeper takes
 * this lock so that, in the normal case, one replica polls the database instead
 * of all of them. Correctness comes from somewhere else entirely -
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} inside a Postgres transaction. If
 * this lock misbehaves and two sweepers run, nothing breaks: they simply cannot
 * claim the same rows.
 *
 * <p>Release is a compare-and-delete in Lua so an instance can never delete a
 * lock that has already expired and been re-acquired by someone else.
 */
@Component
public class RedisLeaderLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLeaderLock.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLeaderLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * A held lock: the key plus the random token that proves ownership.
     * A {@code null} token means "Redis was unreachable and we carried on
     * anyway" - there is nothing to release.
     */
    public record Handle(String key, String token) { }

    /**
     * @return a handle if we got the lock, {@code null} if somebody else has it
     */
    public Handle tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? new Handle(key, token) : null;
        } catch (Exception e) {
            // Redis is down. Since this lock is only an optimisation - the real
            // guarantee is FOR UPDATE SKIP LOCKED inside a Postgres transaction -
            // the right move is to carry on rather than stop. A Redis outage
            // must not freeze the waitlist engine; the worst case is that every
            // replica polls, which is exactly what happens without the lock.
            log.warn("Lock service unavailable, proceeding without it: {}", e.getMessage());
            return new Handle(key, null);
        }
    }

    public void release(Handle handle) {
        if (handle == null || handle.token() == null) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of(handle.key()), handle.token());
        } catch (Exception e) {
            // Not worth failing the job over - the TTL will clean up anyway.
            log.debug("Could not release lock {}: {}", handle.key(), e.getMessage());
        }
    }
}
