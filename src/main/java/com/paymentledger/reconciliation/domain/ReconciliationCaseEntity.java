package com.paymentledger.reconciliation.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_cases")
public class ReconciliationCaseEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 50)
    private ReconciliationOperationType operationType;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "local_status", nullable = false, length = 50)
    private String localStatus;

    @Column(name = "provider_status", length = 50)
    private String providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", length = 100)
    private DiscrepancyType discrepancyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 50)
    private ReconciliationStatus reconciliationStatus;

    @Column(name = "resolution", length = 500)
    private String resolution;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_worker_id", length = 100)
    private String leaseWorkerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    protected ReconciliationCaseEntity() {}

    public ReconciliationCaseEntity(ReconciliationOperationType operationType,
                                    UUID operationId,
                                    String providerReference,
                                    String localStatus,
                                    String correlationId) {
        this.id = UUID.randomUUID();
        this.operationType = operationType;
        this.operationId = operationId;
        this.providerReference = providerReference;
        this.localStatus = localStatus;
        this.reconciliationStatus = ReconciliationStatus.OPEN;
        this.attemptCount = 0;
        this.maxAttempts = 5;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.correlationId = correlationId;
    }

    public boolean isClaimable(Instant now) {
        if (reconciliationStatus == ReconciliationStatus.RESOLVED || reconciliationStatus == ReconciliationStatus.MANUAL_REVIEW) {
            return false;
        }
        if (reconciliationStatus == ReconciliationStatus.OPEN || reconciliationStatus == ReconciliationStatus.RETRY_REQUIRED) {
            return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
        }
        if (reconciliationStatus == ReconciliationStatus.IN_PROGRESS) {
            return leaseExpiresAt != null && leaseExpiresAt.isBefore(now);
        }
        return false;
    }

    public void claim(String workerId, Duration leaseDuration) {
        this.reconciliationStatus = ReconciliationStatus.IN_PROGRESS;
        this.leaseWorkerId = workerId;
        this.leaseExpiresAt = Instant.now().plus(leaseDuration);
        this.updatedAt = Instant.now();
    }

    public void releaseLease() {
        if (this.reconciliationStatus == ReconciliationStatus.IN_PROGRESS) {
            this.reconciliationStatus = ReconciliationStatus.RETRY_REQUIRED;
        }
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.updatedAt = Instant.now();
    }

    public void recordSuccess(String providerStatus, String resolution, DiscrepancyType discrepancy) {
        this.providerStatus = providerStatus;
        this.resolution = resolution;
        this.discrepancyType = discrepancy;
        this.reconciliationStatus = ReconciliationStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.updatedAt = Instant.now();
    }

    public void recordRetry(String providerStatus, String error, Duration backoff, DiscrepancyType discrepancy) {
        this.attemptCount++;
        this.providerStatus = providerStatus;
        this.lastError = error != null && error.length() > 990 ? error.substring(0, 990) : error;
        this.discrepancyType = discrepancy;
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.updatedAt = Instant.now();

        if (this.attemptCount >= this.maxAttempts) {
            this.reconciliationStatus = ReconciliationStatus.MANUAL_REVIEW;
            this.resolution = "Exceeded max attempts (" + this.maxAttempts + "): " + this.lastError;
        } else {
            this.reconciliationStatus = ReconciliationStatus.RETRY_REQUIRED;
            this.nextAttemptAt = Instant.now().plus(backoff);
        }
    }

    public void escalateToManualReview(String providerStatus, String reason, DiscrepancyType discrepancy) {
        this.attemptCount++;
        this.providerStatus = providerStatus;
        this.resolution = reason != null && reason.length() > 490 ? reason.substring(0, 490) : reason;
        this.lastError = this.resolution;
        this.discrepancyType = discrepancy;
        this.reconciliationStatus = ReconciliationStatus.MANUAL_REVIEW;
        this.leaseWorkerId = null;
        this.leaseExpiresAt = null;
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public ReconciliationOperationType getOperationType() { return operationType; }
    public UUID getOperationId() { return operationId; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public String getLocalStatus() { return localStatus; }
    public void setLocalStatus(String localStatus) { this.localStatus = localStatus; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public DiscrepancyType getDiscrepancyType() { return discrepancyType; }
    public void setDiscrepancyType(DiscrepancyType discrepancyType) { this.discrepancyType = discrepancyType; }
    public ReconciliationStatus getReconciliationStatus() { return reconciliationStatus; }
    public void setReconciliationStatus(ReconciliationStatus reconciliationStatus) { this.reconciliationStatus = reconciliationStatus; }
    public String getResolution() { return resolution; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLeaseWorkerId() { return leaseWorkerId; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
}
