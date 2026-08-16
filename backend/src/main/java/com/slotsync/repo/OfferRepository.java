package com.slotsync.repo;

import com.slotsync.domain.Offer;
import com.slotsync.domain.OfferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {

    Optional<Offer> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Offer> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<Offer> findByTenantIdAndStatusOrderByExpiresAtAsc(UUID tenantId, OfferStatus status);

    /**
     * The sweeper's claim step: grab up to {@code batchSize} offers whose
     * deadline has passed, and lock them so no other instance touches them.
     *
     * <p>Returning ids (not entities) keeps this transaction short: we take the
     * locks, then load and mutate each row through JPA in the same transaction.
     */
    @Query(value = """
                   SELECT id FROM offers
                   WHERE status = 'PENDING'
                     AND expires_at <= now()
                   ORDER BY expires_at
                   LIMIT :batchSize
                   FOR UPDATE SKIP LOCKED
                   """, nativeQuery = true)
    List<UUID> lockDueOfferIds(@Param("batchSize") int batchSize);

    /**
     * Which waitlist entries have already been tried for this exact slot?
     * Feeds the "do not offer to the same person twice" exclusion list.
     */
    @Query("""
           select o.waitlistEntryId from Offer o
           where o.resourceId = :resourceId
             and o.startsAt = :startsAt
             and o.endsAt   = :endsAt
             and o.freedAt  = :freedAt
           """)
    List<UUID> findTriedEntryIds(@Param("resourceId") UUID resourceId,
                                 @Param("startsAt") Instant startsAt,
                                 @Param("endsAt") Instant endsAt,
                                 @Param("freedAt") Instant freedAt);

    Optional<Offer> findByBookingIdAndStatus(UUID bookingId, OfferStatus status);

    /**
     * Load one offer and hold a row lock on it until the transaction ends.
     *
     * <p>Used by the claim endpoint. Two people (or one person double-clicking)
     * hitting claim at the same millisecond serialise here: the first sets the
     * status to CLAIMED, the second reads the already-updated row and is told
     * the offer is no longer pending. It also blocks the expiry sweeper from
     * expiring an offer that is mid-claim.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.id = :id")
    Optional<Offer> lockById(@Param("id") UUID id);
}
