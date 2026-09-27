package com.paymentledger.outbox.repository;

import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Atomically locks and returns eligible pending or stale-lease outbox records using SKIP LOCKED.
     * Prevents lock contention among concurrent outbox relay workers.
     *
     * @param now Current timestamp
     * @param staleThreshold Lease expiration threshold (e.g. now - 30s)
     * @param limit Maximum batch size to claim
     * @return Locked eligible outbox events
     */
    @Query(value = """
        SELECT * FROM outbox_events
        WHERE (status = 'PENDING' AND next_attempt_at <= :now)
           OR (status = 'PROCESSING' AND locked_at < :staleThreshold)
        ORDER BY next_attempt_at ASC, created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> findEligibleForClaim(
            @Param("now") Instant now,
            @Param("staleThreshold") Instant staleThreshold,
            @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    List<OutboxEventEntity> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType, String aggregateId);
}
