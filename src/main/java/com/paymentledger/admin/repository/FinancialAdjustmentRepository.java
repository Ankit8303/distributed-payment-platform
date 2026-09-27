package com.paymentledger.admin.repository;

import com.paymentledger.admin.domain.FinancialAdjustmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinancialAdjustmentRepository extends JpaRepository<FinancialAdjustmentEntity, UUID> {

    List<FinancialAdjustmentEntity> findByOperatorId(UUID operatorId);
}
