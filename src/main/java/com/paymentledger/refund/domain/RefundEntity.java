package com.paymentledger.refund.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class RefundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private RefundStatus status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "compensating_ledger_transaction_id")
    private UUID compensatingLedgerTransactionId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundEntity() {}

    public RefundEntity(UUID paymentId, long amountMinor, String currency, String reason) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be strictly positive");
        }
        this.paymentId = paymentId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reason = reason;
        this.status = RefundStatus.REQUESTED;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // State Transitions
    public void transitionToProcessing() {
        if (this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("Refund must be in REQUESTED state to transition to PROCESSING, was: " + this.status);
        }
        this.status = RefundStatus.PROCESSING;
    }

    public void settle(String providerReference, UUID compensatingLedgerTransactionId) {
        if (this.status != RefundStatus.PROCESSING && this.status != RefundStatus.REQUESTED && this.status != RefundStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Refund must be in REQUESTED, PROCESSING, or PENDING_RECONCILIATION to settle, was: " + this.status);
        }
        this.status = RefundStatus.SETTLED;
        if (providerReference != null) {
            this.providerReference = providerReference;
        }
        this.compensatingLedgerTransactionId = compensatingLedgerTransactionId;
    }

    public void fail(String failureReason) {
        if (this.status != RefundStatus.PROCESSING && this.status != RefundStatus.REQUESTED && this.status != RefundStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Refund must be in REQUESTED, PROCESSING, or PENDING_RECONCILIATION to fail, was: " + this.status);
        }
        this.status = RefundStatus.FAILED;
        this.failureReason = failureReason;
    }

    public void markPendingReconciliation(String reason) {
        if (this.status == RefundStatus.PENDING_RECONCILIATION) {
            return;
        }
        if (this.status != RefundStatus.PROCESSING && this.status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("Refund must be in REQUESTED or PROCESSING to mark pending reconciliation, was: " + this.status);
        }
        this.status = RefundStatus.PENDING_RECONCILIATION;
        this.failureReason = reason;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public RefundStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getProviderReference() { return providerReference; }
    public UUID getCompensatingLedgerTransactionId() { return compensatingLedgerTransactionId; }
    public String getFailureReason() { return failureReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
