package com.paymentledger.shared.idempotency;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records", uniqueConstraints = {
    @UniqueConstraint(name = "uq_idempotency_scope", columnNames = {"actor_id", "operation", "idempotency_key"})
})
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false, length = 100)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IdempotencyStatus status;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecordEntity() {}

    public IdempotencyRecordEntity(UUID actorId, String operation, String idempotencyKey, String requestHash, Instant expiresAt) {
        this.actorId = actorId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public void complete(Integer statusCode, String body, UUID resourceId) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatusCode = statusCode;
        this.responseBody = body;
        this.resourceId = resourceId;
    }

    public void fail(Integer statusCode, String body) {
        this.status = IdempotencyStatus.FAILED;
        this.responseStatusCode = statusCode;
        this.responseBody = body;
    }

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public String getOperation() { return operation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public IdempotencyStatus getStatus() { return status; }
    public Integer getResponseStatusCode() { return responseStatusCode; }
    public String getResponseBody() { return responseBody; }
    public UUID getResourceId() { return resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
