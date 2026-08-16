package com.slotsync.web;

import com.slotsync.common.TenantContext;
import com.slotsync.domain.OfferStatus;
import com.slotsync.idempotency.IdempotencyService;
import com.slotsync.repo.OfferRepository;
import com.slotsync.waitlist.OfferService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What a waitlisted customer sees and does.
 *
 * <p>In a real product the claim link would arrive by SMS with a signed token.
 * Here the "offer inbox" in the UI plays that role so the cascade is visible
 * on screen.
 */
@RestController
@RequestMapping("/api/v1/offers")
public class OfferController {

    private final OfferService offerService;
    private final OfferRepository offerRepository;
    private final IdempotencyService idempotency;

    public OfferController(OfferService offerService,
                           OfferRepository offerRepository,
                           IdempotencyService idempotency) {
        this.offerService = offerService;
        this.offerRepository = offerRepository;
        this.idempotency = idempotency;
    }

    @GetMapping
    public List<Dtos.OfferView> list() {
        return offerRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.require())
                .stream().map(Dtos.OfferView::of).toList();
    }

    @GetMapping("/pending")
    public List<Dtos.OfferView> pending() {
        return offerRepository
                .findByTenantIdAndStatusOrderByExpiresAtAsc(TenantContext.require(), OfferStatus.PENDING)
                .stream().map(Dtos.OfferView::of).toList();
    }

    @PostMapping("/{offerId}/claim")
    public Dtos.OfferView claim(
            @PathVariable UUID offerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        UUID tenantId = TenantContext.require();
        return idempotency.run(tenantId, "POST /offers/claim", idempotencyKey, offerId,
                Dtos.OfferView.class,
                () -> Dtos.OfferView.of(offerService.claim(tenantId, offerId)));
    }
}
