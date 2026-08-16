package com.slotsync.repo;

import com.slotsync.domain.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    Optional<WaitlistEntry> findByIdAndTenantId(UUID id, UUID tenantId);

    List<WaitlistEntry> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /**
     * Pick the best person to offer a freed slot to, and lock that row.
     *
     * <p>Three things are happening in this one statement:
     *
     * <ul>
     *   <li><b>ORDER BY priority DESC, missed_offers ASC, created_at ASC</b> -
     *       the fairness rule, backed by a partial index so it is an index scan,
     *       not a sort of the whole table.</li>
     *   <li><b>FOR UPDATE</b> - locks the chosen row until the transaction ends,
     *       so a second worker cannot pick the same person at the same moment.</li>
     *   <li><b>SKIP LOCKED</b> - a second worker does not block waiting for that
     *       lock; it silently skips to the next eligible person. This is what
     *       turns the table into a safe concurrent work queue.</li>
     * </ul>
     *
     * @param excludedIds Postgres array literal, e.g. {@code {uuid,uuid}} or
     *                    {@code {}}. Holds the entries already tried for this
     *                    exact slot, so a cascade never loops back to someone
     *                    who already let it expire.
     */
    @Query(value = """
                   SELECT * FROM waitlist_entries w
                   WHERE w.resource_id = :resourceId
                     AND w.status = 'WAITING'
                     AND w.window_start <= :startsAt
                     AND w.window_end   >= :endsAt
                     AND w.id <> ALL (CAST(:excludedIds AS uuid[]))
                   ORDER BY w.priority DESC, w.missed_offers ASC, w.created_at ASC
                   LIMIT 1
                   FOR UPDATE SKIP LOCKED
                   """, nativeQuery = true)
    Optional<WaitlistEntry> lockNextCandidate(@Param("resourceId") UUID resourceId,
                                              @Param("startsAt") Instant startsAt,
                                              @Param("endsAt") Instant endsAt,
                                              @Param("excludedIds") String excludedIds);
}
