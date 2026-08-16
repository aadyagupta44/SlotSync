package com.slotsync.repo;

import com.slotsync.domain.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    List<Resource> findByTenantIdOrderByName(UUID tenantId);

    /**
     * Always look a resource up together with its tenant. Taking the tenant
     * from the request and putting it in the WHERE clause is what stops one
     * tenant from reading or booking another tenant's rows.
     */
    Optional<Resource> findByIdAndTenantId(UUID id, UUID tenantId);
}
