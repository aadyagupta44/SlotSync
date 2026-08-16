package com.slotsync.waitlist;

import com.slotsync.lock.RedisLeaderLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The clock of the system: every couple of seconds, look for offers whose
 * deadline has passed.
 *
 * <p>Why polling and not a timer? A {@code ScheduledExecutorService} timer
 * lives in one JVM's heap. Restart the process and every pending deadline is
 * silently lost; run three replicas and each one fires its own copy. Deadlines
 * here live in a Postgres column, so they survive restarts, deploys and
 * crashes, and any replica can pick up any overdue offer.
 *
 * <p>The Redis lock below is a politeness optimisation, not a safety mechanism -
 * see {@link RedisLeaderLock}. Even if two replicas sweep simultaneously,
 * {@code FOR UPDATE SKIP LOCKED} guarantees they work on disjoint rows.
 */
@Component
public class OfferExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(OfferExpirySweeper.class);
    private static final String LOCK_KEY = "slotsync:lock:offer-sweeper";

    private final OfferExpiryProcessor processor;
    private final RedisLeaderLock leaderLock;
    private final long intervalMs;

    public OfferExpirySweeper(OfferExpiryProcessor processor,
                              RedisLeaderLock leaderLock,
                              com.slotsync.config.SlotSyncProperties properties) {
        this.processor = processor;
        this.leaderLock = leaderLock;
        this.intervalMs = properties.sweeper().intervalMs();
    }

    @Scheduled(fixedDelayString = "${slotsync.sweeper.interval-ms}")
    public void tick() {
        // TTL slightly longer than one tick: if this instance dies holding the
        // lock, the next tick on another instance picks the work up.
        RedisLeaderLock.Handle handle =
                leaderLock.tryAcquire(LOCK_KEY, Duration.ofMillis(intervalMs * 2));
        if (handle == null) {
            return;   // another replica is on it this round
        }
        try {
            int expired = processor.sweepOnce();
            if (expired > 0) {
                log.info("Sweeper cascaded {} expired offer(s)", expired);
            }
        } catch (Exception e) {
            // Never let a scheduled method throw: Spring would keep rescheduling
            // it, but a noisy stack trace every 2s hides the real problem.
            log.error("Sweeper tick failed", e);
        } finally {
            leaderLock.release(handle);
        }
    }
}
