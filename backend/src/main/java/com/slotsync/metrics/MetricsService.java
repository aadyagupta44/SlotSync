package com.slotsync.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * The numbers that justify the product: how many cancellations were refilled
 * automatically, and how fast.
 *
 * <p>Written as one SQL statement rather than loading rows into Java. Counting
 * and percentiles are what a database is good at, and it keeps the dashboard
 * cheap no matter how much history builds up.
 */
@Service
public class MetricsService {

    private final JdbcTemplate jdbc;

    public MetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param cancellations       confirmed bookings that were later cancelled
     * @param autoRefilled        cancellations a waitlisted customer took over
     * @param refillRatePercent   autoRefilled / cancellations
     * @param medianRefillSeconds median time from cancellation to claim
     * @param p90RefillSeconds    90th percentile of the same
     * @param offersMade          total offers, including ones that timed out
     * @param offersExpired       offers nobody claimed in time
     * @param cascadeRate         share of refills that needed more than one try
     */
    public record Snapshot(long cancellations,
                           long autoRefilled,
                           double refillRatePercent,
                           Double medianRefillSeconds,
                           Double p90RefillSeconds,
                           long offersMade,
                           long offersExpired,
                           long pendingOffers,
                           long waitlistWaiting,
                           double cascadeRate,
                           long outboxPending,
                           long deadLetters) { }

    public Snapshot snapshot(UUID tenantId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM bookings
                    WHERE tenant_id = ? AND status = 'CANCELLED')                       AS cancellations,
                  (SELECT count(*) FROM offers
                    WHERE tenant_id = ? AND status = 'CLAIMED')                         AS auto_refilled,
                  (SELECT percentile_cont(0.5) WITHIN GROUP
                            (ORDER BY EXTRACT(EPOCH FROM (claimed_at - freed_at))::double precision)
                     FROM offers WHERE tenant_id = ? AND status = 'CLAIMED')             AS median_refill,
                  (SELECT percentile_cont(0.9) WITHIN GROUP
                            (ORDER BY EXTRACT(EPOCH FROM (claimed_at - freed_at))::double precision)
                     FROM offers WHERE tenant_id = ? AND status = 'CLAIMED')             AS p90_refill,
                  (SELECT count(*) FROM offers WHERE tenant_id = ?)                      AS offers_made,
                  (SELECT count(*) FROM offers
                    WHERE tenant_id = ? AND status = 'EXPIRED')                          AS offers_expired,
                  (SELECT count(*) FROM offers
                    WHERE tenant_id = ? AND status = 'PENDING')                          AS pending_offers,
                  (SELECT count(*) FROM waitlist_entries
                    WHERE tenant_id = ? AND status = 'WAITING')                          AS waitlist_waiting,
                  (SELECT count(*) FROM offers
                    WHERE tenant_id = ? AND status = 'CLAIMED' AND attempt > 1)          AS cascaded_refills,
                  (SELECT count(*) FROM outbox_events WHERE status = 'PENDING')          AS outbox_pending,
                  (SELECT count(*) FROM dead_letter_events)                              AS dead_letters
                """,
                tenantId, tenantId, tenantId, tenantId, tenantId,
                tenantId, tenantId, tenantId, tenantId);

        long cancellations = asLong(row.get("cancellations"));
        long autoRefilled = asLong(row.get("auto_refilled"));
        long cascaded = asLong(row.get("cascaded_refills"));

        return new Snapshot(
                cancellations,
                autoRefilled,
                cancellations == 0 ? 0.0 : round(100.0 * autoRefilled / cancellations),
                asDouble(row.get("median_refill")),
                asDouble(row.get("p90_refill")),
                asLong(row.get("offers_made")),
                asLong(row.get("offers_expired")),
                asLong(row.get("pending_offers")),
                asLong(row.get("waitlist_waiting")),
                autoRefilled == 0 ? 0.0 : round(100.0 * cascaded / autoRefilled),
                asLong(row.get("outbox_pending")),
                asLong(row.get("dead_letters")));
    }

    private long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private Double asDouble(Object value) {
        return value == null ? null : round(((Number) value).doubleValue());
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
