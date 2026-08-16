package com.slotsync.ratelimit;

import com.slotsync.config.SlotSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A token bucket shared by every backend replica.
 *
 * <p>Counting requests in a local {@code HashMap} would give each replica its
 * own private limit - three replicas would let three times the traffic through,
 * and the limit would reset on every deploy. Keeping the counter in Redis makes
 * the limit a property of the <em>system</em>, not of a process.
 *
 * <p>The whole read-compute-write cycle runs as a Lua script, which Redis
 * executes atomically. Doing it as separate GET and SET calls from Java would
 * be a textbook race: two replicas both read 1 token left and both allow the
 * request.
 *
 * <p>Behaviour when Redis is unreachable: <b>fail open</b>. Rate limiting is a
 * protection, not a feature; refusing all traffic because the limiter is down
 * turns a minor dependency outage into a total one.
 */
@Component
public class RedisTokenBucket {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucket.class);

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key       = KEYS[1]
            local capacity  = tonumber(ARGV[1])
            local refill    = tonumber(ARGV[2])   -- tokens per second
            local now       = tonumber(ARGV[3])   -- seconds, fractional
            local requested = tonumber(ARGV[4])

            local state  = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts     = tonumber(state[2])

            if tokens == nil then
                tokens = capacity
                ts = now
            end

            -- Refill for the time that passed, never above capacity.
            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + (elapsed * refill))

            local allowed = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            end

            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, 3600)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SlotSyncProperties properties;

    public RedisTokenBucket(StringRedisTemplate redis, SlotSyncProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean tryConsume(String bucketKey) {
        SlotSyncProperties.RateLimit config = properties.ratelimit();
        try {
            Long allowed = redis.execute(SCRIPT,
                    List.of("slotsync:rl:" + bucketKey),
                    String.valueOf(config.capacity()),
                    String.valueOf(config.refillPerSecond()),
                    String.valueOf(System.currentTimeMillis() / 1000.0),
                    "1");
            return allowed == null || allowed == 1L;
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return true;
        }
    }
}
