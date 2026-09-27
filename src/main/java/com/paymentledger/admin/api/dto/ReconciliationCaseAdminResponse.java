package com.paymentledger.admin.api.dto;

import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationCaseAdminResponse(
        UUID id,
        String operationType,
        UUID operationId,
        String providerReference,
        String localStatus,
        String reconciliationStatus,
        String discrepancyType,
        int attemptCount,
        Instant nextAttemptAt,
        Instant resolvedAt,
        String workerId,
        String correlationId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReconciliationCaseAdminResponse fromEntity(ReconciliationCaseEntity c) {
        return new ReconciliationCaseAdminResponse(
                c.getId(),
                c.getOperationType().name(),
                c.getOperationId(),
                c.getProviderReference(),
                c.getLocalStatus(),
                c.getReconciliationStatus().name(),
                c.getDiscrepancyType() != null ? c.getDiscrepancyType().name() : null,
                c.getAttemptCount(),
                c.getNextAttemptAt(),
                c.getResolvedAt(),
                c.getLeaseWorkerId(),
                c.getCorrelationId(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
