package com.slotsync.repo;

import com.slotsync.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claim a batch of unpublished events.
     *
     * <p>Same {@code FOR UPDATE SKIP LOCKED} trick as the offer sweeper: every
     * backend replica can run this loop at the same time and they will never
     * publish the same event twice, because a locked row is invisible to the
     * others rather than blocking them.
     */
    @Query(value = """
                   SELECT * FROM outbox_events
                   WHERE status = 'PENDING'
                     AND next_attempt_at <= now()
                   ORDER BY id
                   LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED
                   """, nativeQuery = true)
    List<OutboxEvent> lockDueBatch(@Param("batchSize") int batchSize);

    long countByStatus(String status);
}
