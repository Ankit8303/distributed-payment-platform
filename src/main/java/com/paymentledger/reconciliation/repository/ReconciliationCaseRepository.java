package com.paymentledger.reconciliation.repository;

import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;
import com.paymentledger.reconciliation.domain.ReconciliationOperationType;
import com.paymentledger.reconciliation.domain.ReconciliationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationCaseRepository extends JpaRepository<ReconciliationCaseEntity, UUID> {

    Optional<ReconciliationCaseEntity> findByOperationTypeAndOperationId(ReconciliationOperationType operationType, UUID operationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ReconciliationCaseEntity c WHERE c.id = :id")
    Optional<ReconciliationCaseEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<ReconciliationCaseEntity> findByReconciliationStatus(ReconciliationStatus status, Pageable pageable);

    @Query(value = "SELECT * FROM reconciliation_cases " +
            "WHERE (reconciliation_status IN ('OPEN', 'RETRY_REQUIRED') AND next_attempt_at <= :now) " +
            "   OR (reconciliation_status = 'IN_PROGRESS' AND lease_expires_at < :now) " +
            "ORDER BY next_attempt_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<ReconciliationCaseEntity> claimEligibleCasesNative(@Param("now") Instant now, @Param("limit") int limit);
}
