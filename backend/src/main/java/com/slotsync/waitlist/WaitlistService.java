package com.slotsync.waitlist;

import com.slotsync.common.ApiException;
import com.slotsync.domain.Customer;
import com.slotsync.domain.WaitlistEntry;
import com.slotsync.domain.WaitlistStatus;
import com.slotsync.repo.CustomerRepository;
import com.slotsync.repo.ResourceRepository;
import com.slotsync.repo.WaitlistEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Joining and leaving the queue. The interesting part is in {@link OfferService}. */
@Service
public class WaitlistService {

    private final WaitlistEntryRepository waitlistRepository;
    private final CustomerRepository customerRepository;
    private final ResourceRepository resourceRepository;

    public WaitlistService(WaitlistEntryRepository waitlistRepository,
                           CustomerRepository customerRepository,
                           ResourceRepository resourceRepository) {
        this.waitlistRepository = waitlistRepository;
        this.customerRepository = customerRepository;
        this.resourceRepository = resourceRepository;
    }

    @Transactional
    public WaitlistEntry join(UUID tenantId, UUID resourceId, UUID customerId,
                              Instant windowStart, Instant windowEnd, int priority) {
        resourceRepository.findByIdAndTenantId(resourceId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Resource"));
        Customer customer = customerRepository.findByIdAndTenantId(customerId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Customer"));

        if (!windowEnd.isAfter(windowStart)) {
            throw ApiException.badRequest("INVALID_WINDOW", "windowEnd must be after windowStart");
        }

        WaitlistEntry entry = new WaitlistEntry();
        entry.setTenantId(tenantId);
        entry.setResourceId(resourceId);
        entry.setCustomerId(customer.getId());
        entry.setWindowStart(windowStart);
        entry.setWindowEnd(windowEnd);
        entry.setPriority(priority);
        entry.setStatus(WaitlistStatus.WAITING);
        return waitlistRepository.save(entry);
    }

    @Transactional
    public WaitlistEntry withdraw(UUID tenantId, UUID entryId) {
        WaitlistEntry entry = waitlistRepository.findByIdAndTenantId(entryId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Waitlist entry"));
        entry.setStatus(WaitlistStatus.WITHDRAWN);
        return waitlistRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<WaitlistEntry> list(UUID tenantId) {
        return waitlistRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }
}
