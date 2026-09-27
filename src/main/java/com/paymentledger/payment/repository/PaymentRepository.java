package com.paymentledger.payment.repository;

import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
    Page<PaymentEntity> findByPayerAccountId(UUID payerAccountId, Pageable pageable);
    Page<PaymentEntity> findByPayeeAccountId(UUID payeeAccountId, Pageable pageable);
    Optional<PaymentEntity> findByIdAndPayerAccountId(UUID id, UUID payerAccountId);
    Optional<PaymentEntity> findByIdAndPayeeAccountId(UUID id, UUID payeeAccountId);
    Optional<PaymentEntity> findByIdempotencyScopeAndIdempotencyKey(String idempotencyScope, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(@Param("id") UUID id);

    java.util.List<PaymentEntity> findByStatus(PaymentStatus status);

    Page<PaymentEntity> findByStatus(PaymentStatus status, Pageable pageable);

    long countByStatus(PaymentStatus status);
}
