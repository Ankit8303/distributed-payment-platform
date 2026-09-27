package com.paymentledger.admin.api.dto;

import com.paymentledger.admin.domain.AdminAuditLogEntity;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditLogResponse(
        UUID id,
        UUID actorUserId,
        String actorRole,
        String action,
        String resourceType,
        String resourceId,
        String reason,
        String correlationId,
        String requestId,
        String beforeState,
        String afterState,
        Instant createdAt,
        String metadata
) {
    public static AdminAuditLogResponse fromEntity(AdminAuditLogEntity audit) {
        return new AdminAuditLogResponse(
                audit.getId(),
                audit.getActorUserId(),
                audit.getActorRole(),
                audit.getAction(),
                audit.getResourceType(),
                audit.getResourceId(),
                audit.getReason(),
                audit.getCorrelationId(),
                audit.getRequestId(),
                audit.getBeforeState(),
                audit.getAfterState(),
                audit.getCreatedAt(),
                audit.getMetadata()
        );
    }
}
