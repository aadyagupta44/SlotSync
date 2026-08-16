package com.slotsync.web;

import com.slotsync.booking.AvailabilityService;
import com.slotsync.common.TenantContext;
import com.slotsync.repo.CustomerRepository;
import com.slotsync.repo.ResourceRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only lookups the UI needs: resources, customers, and the day grid. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final ResourceRepository resourceRepository;
    private final CustomerRepository customerRepository;
    private final AvailabilityService availabilityService;

    public CatalogController(ResourceRepository resourceRepository,
                             CustomerRepository customerRepository,
                             AvailabilityService availabilityService) {
        this.resourceRepository = resourceRepository;
        this.customerRepository = customerRepository;
        this.availabilityService = availabilityService;
    }

    @GetMapping("/resources")
    public List<Dtos.ResourceView> resources() {
        return resourceRepository.findByTenantIdOrderByName(TenantContext.require())
                .stream().map(Dtos.ResourceView::of).toList();
    }

    @GetMapping("/customers")
    public List<Dtos.CustomerView> customers() {
        return customerRepository.findByTenantIdOrderByName(TenantContext.require())
                .stream().map(Dtos.CustomerView::of).toList();
    }

    /**
     * The availability grid for one resource on one day.
     * Slots are generated on the fly from the resource's opening hours.
     */
    @GetMapping("/resources/{resourceId}/availability")
    public List<Dtos.SlotView> availability(
            @PathVariable UUID resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        UUID tenantId = TenantContext.require();
        LocalDate day = date != null ? date : LocalDate.now(
                java.time.ZoneId.of(availabilityService.requireResource(resourceId, tenantId).getTimezone()));

        return availabilityService.availability(tenantId, resourceId, day)
                .stream().map(Dtos.SlotView::of).toList();
    }
}
