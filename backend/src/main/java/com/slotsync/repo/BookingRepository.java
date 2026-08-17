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
     * Queue behind anyone else trying to book this exact slot right now.
     *
     * <p><b>This does not provide the no-double-booking guarantee</b> — the
     * {@code bookings_no_overlap} exclusion constraint does, and it still
     * decides every outcome. This exists purely to fix a throughput collapse.
     *
     * <p>Concurrent inserts of overlapping ranges contend on the GiST index
     * itself, and they can acquire index locks in different orders. That forms
     * genuine deadlock cycles, which Postgres only breaks after
     * {@code deadlock_timeout} (one second by default), aborting contenders. At
     * 60-way contention every single contender was aborted — a free slot was
     * reported as taken to all of them and stayed empty — and each attempt held
     * a connection for a second while it happened, saturating the pool.
     *
     * <p>An advisory lock keyed on the slot removes the cycle: every contender
     * waits on <b>one</b> lock taken in <b>one</b> order, so they queue in
     * microseconds rather than deadlocking for a second. The winner inserts and
     * commits; the next in line then hits the exclusion constraint immediately
     * and gets a clean 409. Same guarantee, same answer, no lock convoy.
     *
     * <p>{@code pg_advisory_xact_lock} releases automatically when the
     * transaction ends, so there is no unlock to forget. Different slots hash to
     * different keys and proceed in parallel.
     */
    @Query(value = "select pg_advisory_xact_lock(hashtext(:slotKey))", nativeQuery = true)
    void lockSlotForBooking(@Param("slotKey") String slotKey);

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
