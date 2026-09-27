package com.paymentledger.admin.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "financial_adjustments")
public class FinancialAdjustmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "compensating_ledger_transaction_id", nullable = false)
    private UUID compensatingLedgerTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinancialAdjustmentEntity() {}

    public FinancialAdjustmentEntity(UUID sourceAccountId,
                                     UUID targetAccountId,
                                     long amountMinor,
                                     String currency,
                                     String reason,
                                     UUID operatorId,
                                     UUID compensatingLedgerTransactionId) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Adjustment amount must be strictly positive");
        }
        if (sourceAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("Source and target accounts must be distinct");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Adjustment reason is strictly required");
        }
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.reason = reason;
        this.operatorId = operatorId;
        this.compensatingLedgerTransactionId = compensatingLedgerTransactionId;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getTargetAccountId() { return targetAccountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getOperatorId() { return operatorId; }
    public UUID getCompensatingLedgerTransactionId() { return compensatingLedgerTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
}
