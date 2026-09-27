package com.paymentledger.payout.repository;

import com.paymentledger.payout.domain.PayoutEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<PayoutEntity, UUID> {

    List<PayoutEntity> findByAccountId(UUID accountId);

    org.springframework.data.domain.Page<PayoutEntity> findByAccountId(UUID accountId, org.springframework.data.domain.Pageable pageable);

    List<PayoutEntity> findByStatus(com.paymentledger.payout.domain.PayoutStatus status);

    org.springframework.data.domain.Page<PayoutEntity> findByStatus(com.paymentledger.payout.domain.PayoutStatus status, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PayoutEntity p WHERE p.id = :id")
    java.util.Optional<PayoutEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
