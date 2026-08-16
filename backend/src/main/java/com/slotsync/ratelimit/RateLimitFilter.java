package com.slotsync.ratelimit;

import com.slotsync.common.TenantContext;
import com.slotsync.config.SlotSyncProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Per-tenant rate limiting at the edge.
 *
 * <p>Runs after {@link com.slotsync.common.TenantFilter} (hence
 * {@code @Order(2)}) so the bucket can be keyed by tenant: one noisy tenant
 * hammering the API cannot degrade the others. Requests with no resolvable
 * tenant fall back to the client IP.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisTokenBucket bucket;
    private final SlotSyncProperties properties;

    public RateLimitFilter(RedisTokenBucket bucket, SlotSyncProperties properties) {
        this.bucket = bucket;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The live event stream is a single long-lived connection, not traffic
        // worth metering, and actuator must stay reachable for health checks.
        return !request.getRequestURI().startsWith("/api/")
                || request.getRequestURI().startsWith("/api/v1/stream")
                || !properties.ratelimit().enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UUID tenantId = TenantContext.get();
        String key = tenantId != null ? "tenant:" + tenantId : "ip:" + request.getRemoteAddr();

        if (bucket.tryConsume(key)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "1");
        response.getWriter().write("""
                {"code":"RATE_LIMITED","message":"Too many requests - slow down."}""");
    }
}
