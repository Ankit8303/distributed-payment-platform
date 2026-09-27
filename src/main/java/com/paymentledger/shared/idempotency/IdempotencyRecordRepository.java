package com.paymentledger.shared.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {
    Optional<IdempotencyRecordEntity> findByActorIdAndOperationAndIdempotencyKey(UUID actorId, String operation, String idempotencyKey);
}
