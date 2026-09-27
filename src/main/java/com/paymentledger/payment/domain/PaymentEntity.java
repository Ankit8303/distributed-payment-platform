package com.paymentledger.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "idempotency_scope", nullable = false, length = 255)
    private String idempotencyScope;

    @Column(name = "payer_account_id", nullable = false)
    private UUID payerAccountId;

    @Column(name = "payee_account_id", nullable = false)
    private UUID payeeAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "fee_amount_minor", nullable = false)
    private long feeAmountMinor = 0L;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PaymentStatus status;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {}

    public PaymentEntity(String idempotencyKey, String idempotencyScope, UUID payerAccountId, UUID payeeAccountId, long amountMinor, String currency) {
        this.idempotencyKey = idempotencyKey;
        this.idempotencyScope = idempotencyScope;
        this.payerAccountId = payerAccountId;
        this.payeeAccountId = payeeAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
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
    public void authorize() {
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException("Payment must be in CREATED state to authorize");
        }
        this.status = PaymentStatus.AUTHORIZING;
    }

    public void authorizationSucceeded(String providerRef) {
        if (this.status != PaymentStatus.AUTHORIZING) {
            throw new IllegalStateException("Payment must be in AUTHORIZING state to succeed authorization");
        }
        this.status = PaymentStatus.AUTHORIZED;
        this.providerReference = providerRef;
    }

    public void authorizationDeclined(String reason) {
        if (this.status != PaymentStatus.AUTHORIZING) {
            throw new IllegalStateException("Payment must be in AUTHORIZING state to be declined");
        }
        this.status = PaymentStatus.DECLINED;
        this.failureReason = reason;
    }

    public void capture() {
        if (this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("Payment must be in AUTHORIZED state to capture");
        }
        this.status = PaymentStatus.CAPTURING;
    }

    public void captureSucceeded(String captureProviderRef) {
        if (this.status != PaymentStatus.CAPTURING && this.status != PaymentStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Payment must be in CAPTURING or PENDING_RECONCILIATION state to settle");
        }
        this.status = PaymentStatus.SETTLED;
        if (captureProviderRef != null) {
            this.providerReference = captureProviderRef;
        }
    }

    public void markPendingReconciliation() {
        if (this.status == PaymentStatus.PENDING_RECONCILIATION) {
            return;
        }
        if (this.status != PaymentStatus.CAPTURING && this.status != PaymentStatus.AUTHORIZING && this.status != PaymentStatus.SETTLED && this.status != PaymentStatus.CREATED && this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("Payment must be in CREATED, AUTHORIZING, AUTHORIZED, CAPTURING, or SETTLED state to mark pending reconciliation");
        }
        this.status = PaymentStatus.PENDING_RECONCILIATION;
    }

    public void captureFailed(String reason) {
        if (this.status != PaymentStatus.CAPTURING && this.status != PaymentStatus.PENDING_RECONCILIATION) {
            throw new IllegalStateException("Payment must be in CAPTURING or PENDING_RECONCILIATION state to fail");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }
    
    public void expire() {
        if (this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("Payment must be in AUTHORIZED state to expire");
        }
        this.status = PaymentStatus.EXPIRED;
    }

    // Getters
    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getIdempotencyScope() { return idempotencyScope; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public UUID getPayeeAccountId() { return payeeAccountId; }
    public long getAmountMinor() { return amountMinor; }
    public long getFeeAmountMinor() { return feeAmountMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setFeeAmountMinor(long feeAmountMinor) {
        if (feeAmountMinor < 0 || feeAmountMinor >= this.amountMinor) {
            throw new IllegalArgumentException("Fee must be non-negative and less than amount");
        }
        this.feeAmountMinor = feeAmountMinor;
    }
}
