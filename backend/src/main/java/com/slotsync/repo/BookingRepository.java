package com.slotsync.repo;

import com.slotsync.domain.Booking;
import com.slotsync.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Everything that occupies part of a time window on one resource.
     * Used to paint the availability grid.
     *
     * <p>Half-open overlap test: two ranges overlap when
     * {@code a.start < b.end AND a.end > b.start}. 10:00-10:30 and 10:30-11:00
     * therefore do not overlap, which is what we want for back-to-back slots.
     */
    @Query("""
           select b from Booking b
           where b.resourceId = :resourceId
             and b.status in :statuses
             and b.startsAt < :windowEnd
             and b.endsAt   > :windowStart
           order by b.startsAt
           """)
    List<Booking> findOverlapping(@Param("resourceId") UUID resourceId,
                                  @Param("windowStart") Instant windowStart,
                                  @Param("windowEnd") Instant windowEnd,
                                  @Param("statuses") Collection<BookingStatus> statuses);

    // Two methods rather than one with an "is null or" clause: Postgres cannot
    // infer the type of a null uuid parameter, so that pattern fails at runtime.
    List<Booking> findByTenantIdOrderByStartsAtDesc(UUID tenantId);

    List<Booking> findByTenantIdAndResourceIdOrderByStartsAtDesc(UUID tenantId, UUID resourceId);

    List<Booking> findByCustomerIdOrderByStartsAtDesc(UUID customerId);
}
