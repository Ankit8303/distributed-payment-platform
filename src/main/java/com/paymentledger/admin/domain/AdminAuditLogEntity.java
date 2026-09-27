package com.paymentledger.admin.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable entity representing an administrative audit log event.
 * Maps to the {@code admin_audit_logs} table.
 */
@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLogEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_role", nullable = false, updatable = false, length = 50)
    private String actorRole;

    @Column(name = "action", nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false, length = 100)
    private String resourceId;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;

    @Column(name = "request_id", updatable = false, length = 100)
    private String requestId;

    @Column(name = "before_state", updatable = false, length = 255)
    private String beforeState;

    @Column(name = "after_state", updatable = false, length = 255)
    private String afterState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "metadata", updatable = false, columnDefinition = "TEXT")
    private String metadata;

    protected AdminAuditLogEntity() {
    }

    public AdminAuditLogEntity(UUID id,
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
                               String metadata) {
        this.id = id != null ? id : UUID.randomUUID();
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.reason = reason;
        this.correlationId = correlationId;
        this.requestId = requestId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.createdAt = Instant.now();
        this.metadata = metadata;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getMetadata() {
        return metadata;
    }
}
