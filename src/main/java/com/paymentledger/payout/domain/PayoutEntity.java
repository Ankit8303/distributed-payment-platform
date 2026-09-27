package com.paymentledger.payout.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payouts")
public class PayoutEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PayoutStatus status;

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

    protected PayoutEntity() {}

    public PayoutEntity(UUID accountId, long amountMinor, String currency) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Payout amount must be strictly positive");
        }
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = PayoutStatus.REQUESTED;
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

    public void transitionToProcessing() {
        if (this.status != PayoutStatus.REQUESTED) {
            throw new IllegalStateException("Payout must be in REQUESTED state to transition to PROCESSING, was: " + this.status);
        }
        this.status = PayoutStatus.PROCESSING;
    }

    public void settle(String providerReference, UUID compensatingLedgerTransactionId) {
        if (this.status != PayoutStatus.PROCESSING && this.status != PayoutStatus.REQUESTED && this.status != PayoutStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Payout must be in REQUESTED, PROCESSING, or PENDING_RECONCILIATION to settle, was: " + this.status);
        }
        this.status = PayoutStatus.SETTLED;
        if (providerReference != null) {
            this.providerReference = providerReference;
        }
        this.compensatingLedgerTransactionId = compensatingLedgerTransactionId;
    }

    public void fail(String failureReason) {
        if (this.status != PayoutStatus.PROCESSING && this.status != PayoutStatus.REQUESTED && this.status != PayoutStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Payout must be in REQUESTED, PROCESSING, or PENDING_RECONCILIATION to fail, was: " + this.status);
        }
        this.status = PayoutStatus.FAILED;
        this.failureReason = failureReason;
    }

    public void markPendingReconciliation(String failureReason) {
        if (this.status == PayoutStatus.PENDING_RECONCILIATION) {
            return;
        }
        if (this.status != PayoutStatus.PROCESSING && this.status != PayoutStatus.REQUESTED) {
            throw new IllegalStateException("Payout must be in REQUESTED or PROCESSING to mark pending reconciliation, was: " + this.status);
        }
        this.status = PayoutStatus.PENDING_RECONCILIATION;
        this.failureReason = failureReason;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PayoutStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public UUID getCompensatingLedgerTransactionId() { return compensatingLedgerTransactionId; }
    public String getFailureReason() { return failureReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
