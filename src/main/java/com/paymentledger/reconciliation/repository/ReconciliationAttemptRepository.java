package com.paymentledger.reconciliation.repository;

import com.paymentledger.reconciliation.domain.ReconciliationAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationAttemptRepository extends JpaRepository<ReconciliationAttemptEntity, UUID> {

    List<ReconciliationAttemptEntity> findByReconciliationCaseIdOrderByAttemptNumberAsc(UUID reconciliationCaseId);
}
