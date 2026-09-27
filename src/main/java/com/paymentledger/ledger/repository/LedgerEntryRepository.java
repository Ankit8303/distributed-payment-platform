package com.paymentledger.ledger.repository;

import com.paymentledger.ledger.domain.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {
    
    @Query("SELECT COALESCE(MAX(e.sequenceNumber), 0) FROM LedgerEntryEntity e WHERE e.accountId = :accountId")
    long getMaxSequenceNumberForAccount(@Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amountMinor ELSE -e.amountMinor END), 0) FROM LedgerEntryEntity e WHERE e.accountId = :accountId")
    long calculateLedgerBalanceMinor(@Param("accountId") UUID accountId);

    java.util.List<LedgerEntryEntity> findByLedgerTransaction_Id(UUID ledgerTransactionId);

    org.springframework.data.domain.Page<LedgerEntryEntity> findByAccountId(UUID accountId, org.springframework.data.domain.Pageable pageable);

    java.util.List<LedgerEntryEntity> findByAccountIdOrderBySequenceNumberAsc(UUID accountId);
}
