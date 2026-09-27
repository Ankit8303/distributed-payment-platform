package com.paymentledger.refund.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reversals")
public class ReversalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ReversalStatus status;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

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

    protected ReversalEntity() {}

    public ReversalEntity(UUID paymentId, long amountMinor, String currency, String reason) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Reversal amount must be strictly positive");
        }
        this.paymentId = paymentId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reason = reason;
        this.status = ReversalStatus.COMPLETED;
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

    public void complete(UUID compensatingLedgerTransactionId) {
        this.status = ReversalStatus.COMPLETED;
        this.compensatingLedgerTransactionId = compensatingLedgerTransactionId;
    }

    public void fail(String failureReason) {
        this.status = ReversalStatus.FAILED;
        this.failureReason = failureReason;
    }

    public void markPendingReconciliation(String failureReason) {
        this.status = ReversalStatus.PENDING_RECONCILIATION;
        this.failureReason = failureReason;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public ReversalStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public UUID getCompensatingLedgerTransactionId() { return compensatingLedgerTransactionId; }
    public String getFailureReason() { return failureReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
