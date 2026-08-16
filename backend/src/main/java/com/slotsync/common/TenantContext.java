package com.slotsync.common;

import java.util.UUID;

/**
 * Holds the current request's tenant so services do not have to thread it
 * through every method signature.
 *
 * <p>A {@link ThreadLocal} is safe here because Spring MVC handles one request
 * on one thread and {@link TenantFilter} always clears it in a
 * {@code finally} block. Background jobs (sweeper, outbox relay, Kafka
 * consumer) never read this - they take the tenant id from the row or the
 * event they are processing, because there is no HTTP request behind them.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() { }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw ApiException.badRequest("TENANT_REQUIRED",
                    "Missing or unknown X-Tenant-Slug header");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
