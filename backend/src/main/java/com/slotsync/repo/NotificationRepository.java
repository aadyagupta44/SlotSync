package com.slotsync.repo;

import com.slotsync.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Limit limit);
}
