package com.slotsync.waitlist;

import com.slotsync.config.SlotSyncProperties;
import com.slotsync.domain.Offer;
import com.slotsync.repo.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Does one pass over the expired offers.
 *
 * <p>Split out from {@link OfferExpirySweeper} on purpose: Spring's
 * {@code @Transactional} works through a proxy, so a scheduled method calling a
 * transactional method <em>on itself</em> would silently run with no
 * transaction at all. Two beans, one call across the boundary, no surprise.
 */
@Component
public class OfferExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(OfferExpiryProcessor.class);

    private final OfferRepository offerRepository;
    private final OfferService offerService;
    private final SlotSyncProperties properties;

    public OfferExpiryProcessor(OfferRepository offerRepository,
                                OfferService offerService,
                                SlotSyncProperties properties) {
        this.offerRepository = offerRepository;
        this.offerService = offerService;
        this.properties = properties;
    }

    /**
     * Claim a batch of overdue offers and expire each one.
     *
     * <p>{@code lockDueOfferIds} is the important line. It does three jobs at
     * once: it filters on the database's own clock (so instances with skewed
     * system clocks cannot expire things early), it locks the rows it returns,
     * and {@code SKIP LOCKED} means a second sweeper takes a different batch
     * rather than waiting. That is what lets this run on every replica safely.
     *
     * @return how many offers were expired
     */
    @Transactional
    public int sweepOnce() {
        List<UUID> dueIds = offerRepository.lockDueOfferIds(properties.sweeper().batchSize());
        if (dueIds.isEmpty()) {
            return 0;
        }
        int expired = 0;
        for (UUID id : dueIds) {
            Offer offer = offerRepository.findById(id).orElse(null);
            if (offer == null) {
                continue;
            }
            offerService.expire(offer);
            expired++;
        }
        log.debug("Sweeper expired {} offer(s)", expired);
        return expired;
    }
}
