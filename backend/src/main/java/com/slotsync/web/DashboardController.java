package com.slotsync.web;

import com.slotsync.common.TenantContext;
import com.slotsync.metrics.MetricsService;
import com.slotsync.notify.NotificationService;
import com.slotsync.stream.SseHub;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Dashboard endpoints: the numbers, the fake inbox, and the live event feed. */
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    private final MetricsService metricsService;
    private final NotificationService notificationService;
    private final SseHub sseHub;

    public DashboardController(MetricsService metricsService,
                               NotificationService notificationService,
                               SseHub sseHub) {
        this.metricsService = metricsService;
        this.notificationService = notificationService;
        this.sseHub = sseHub;
    }

    @GetMapping("/metrics")
    public MetricsService.Snapshot metrics() {
        return metricsService.snapshot(TenantContext.require());
    }

    @GetMapping("/notifications")
    public List<Dtos.NotificationView> notifications(
            @RequestParam(defaultValue = "25") int limit) {
        return notificationService.recent(TenantContext.require(), Math.min(limit, 200))
                .stream().map(Dtos.NotificationView::of).toList();
    }

    /**
     * Live feed of domain events for this tenant.
     *
     * <p>Returning an {@link SseEmitter} releases the servlet thread
     * immediately - the connection stays open but no thread is parked on it,
     * so a few thousand watching browsers do not need a few thousand threads.
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream() {
        return sseHub.subscribe(TenantContext.require());
    }
}
