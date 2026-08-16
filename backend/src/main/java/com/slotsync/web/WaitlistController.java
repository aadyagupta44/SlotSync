package com.slotsync.web;

import com.slotsync.common.TenantContext;
import com.slotsync.idempotency.IdempotencyService;
import com.slotsync.waitlist.WaitlistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;
    private final IdempotencyService idempotency;

    public WaitlistController(WaitlistService waitlistService, IdempotencyService idempotency) {
        this.waitlistService = waitlistService;
        this.idempotency = idempotency;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.WaitlistView join(
            @Valid @RequestBody Dtos.JoinWaitlistRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        UUID tenantId = TenantContext.require();
        return idempotency.run(tenantId, "POST /waitlist", idempotencyKey, request,
                Dtos.WaitlistView.class,
                () -> Dtos.WaitlistView.of(waitlistService.join(
                        tenantId, request.resourceId(), request.customerId(),
                        request.windowStart(), request.windowEnd(), request.priority())));
    }

    @GetMapping
    public List<Dtos.WaitlistView> list() {
        return waitlistService.list(TenantContext.require())
                .stream().map(Dtos.WaitlistView::of).toList();
    }

    @DeleteMapping("/{entryId}")
    public Dtos.WaitlistView withdraw(@PathVariable UUID entryId) {
        return Dtos.WaitlistView.of(waitlistService.withdraw(TenantContext.require(), entryId));
    }
}
