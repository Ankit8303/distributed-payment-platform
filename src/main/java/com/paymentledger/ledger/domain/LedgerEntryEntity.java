package com.paymentledger.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_transaction_id", nullable = false)
    private LedgerTransactionEntity ledgerTransaction;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private LedgerEntryDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {}

    public LedgerEntryEntity(UUID accountId, LedgerEntryDirection direction, long amountMinor, String currency, long sequenceNumber) {
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.sequenceNumber = sequenceNumber;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public LedgerTransactionEntity getLedgerTransaction() { return ledgerTransaction; }
    void setLedgerTransaction(LedgerTransactionEntity ledgerTransaction) { this.ledgerTransaction = ledgerTransaction; }
    public UUID getAccountId() { return accountId; }
    public LedgerEntryDirection getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public long getSequenceNumber() { return sequenceNumber; }
    public Instant getCreatedAt() { return createdAt; }
}
