package com.slotsync.common;

import com.slotsync.repo.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns the {@code X-Tenant-Slug} header into a tenant id for the rest of the
 * request.
 *
 * <p>In a real deployment this would be a JWT claim rather than a header -
 * authentication is deliberately out of scope. What matters architecturally is
 * that the tenant is established once, at the edge, and every query downstream
 * filters on it.
 */
@Component
@Order(1)
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant-Slug";

    private final TenantRepository tenantRepository;

    public TenantFilter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String slug = request.getHeader(HEADER);
            if (slug == null || slug.isBlank()) {
                // The browser's EventSource API cannot send custom headers, so
                // the SSE endpoint identifies its tenant with ?tenant=... . Same
                // resolution, different carrier.
                slug = request.getParameter("tenant");
            }
            if (slug != null && !slug.isBlank()) {
                tenantRepository.findBySlug(slug.trim())
                        .ifPresent(t -> TenantContext.set(t.getId()));
            }
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled and reused. Forgetting this line is how
            // request N+1 silently inherits request N's tenant.
            TenantContext.clear();
        }
    }
}
