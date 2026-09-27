package com.paymentledger.refund.repository;

import com.paymentledger.refund.domain.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<RefundEntity, UUID> {

    List<RefundEntity> findByPaymentId(UUID paymentId);

    org.springframework.data.domain.Page<RefundEntity> findByPaymentId(UUID paymentId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COALESCE(SUM(r.amountMinor), 0) FROM RefundEntity r WHERE r.paymentId = :paymentId AND r.status IN ('SETTLED', 'PROCESSING')")
    long sumSettledAndProcessingRefundsForPayment(@Param("paymentId") UUID paymentId);

    List<RefundEntity> findByStatus(com.paymentledger.refund.domain.RefundStatus status);

    org.springframework.data.domain.Page<RefundEntity> findByStatus(com.paymentledger.refund.domain.RefundStatus status, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundEntity r WHERE r.id = :id")
    java.util.Optional<RefundEntity> findByIdForUpdate(@Param("id") UUID id);
}
