package com.paymentledger.ledger.repository;

import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {
    boolean existsBySourceReferenceIdAndSourceReferenceType(UUID sourceReferenceId, String sourceReferenceType);

    java.util.Optional<LedgerTransactionEntity> findBySourceReferenceId(UUID sourceReferenceId);

    org.springframework.data.domain.Page<LedgerTransactionEntity> findBySourceReferenceType(String sourceReferenceType, org.springframework.data.domain.Pageable pageable);
}
