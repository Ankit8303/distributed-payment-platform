package com.paymentledger.reconciliation.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_attempts")
public class ReconciliationAttemptEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "reconciliation_case_id", nullable = false)
    private UUID reconciliationCaseId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", nullable = false, length = 100)
    private String workerId;

    @Column(name = "provider_status", length = 50)
    private String providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", length = 100)
    private DiscrepancyType discrepancyType;

    @Column(name = "action_taken", nullable = false, length = 100)
    private String actionTaken;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationAttemptEntity() {}

    public ReconciliationAttemptEntity(UUID reconciliationCaseId,
                                       int attemptNumber,
                                       String workerId,
                                       String providerStatus,
                                       DiscrepancyType discrepancyType,
                                       String actionTaken,
                                       String status,
                                       String errorMessage) {
        this.id = UUID.randomUUID();
        this.reconciliationCaseId = reconciliationCaseId;
        this.attemptNumber = attemptNumber;
        this.workerId = workerId;
        this.providerStatus = providerStatus;
        this.discrepancyType = discrepancyType;
        this.actionTaken = actionTaken;
        this.status = status;
        this.errorMessage = errorMessage != null && errorMessage.length() > 990 ? errorMessage.substring(0, 990) : errorMessage;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReconciliationCaseId() { return reconciliationCaseId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getWorkerId() { return workerId; }
    public String getProviderStatus() { return providerStatus; }
    public DiscrepancyType getDiscrepancyType() { return discrepancyType; }
    public String getActionTaken() { return actionTaken; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
