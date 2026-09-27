package com.paymentledger.refund.repository;

import com.paymentledger.refund.domain.ReversalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReversalRepository extends JpaRepository<ReversalEntity, UUID> {

    Optional<ReversalEntity> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);
}
