package com.paymentledger.ledger.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private LedgerTransactionType transactionType;

    @Column(name = "source_reference_id", nullable = false)
    private UUID sourceReferenceId;

    @Column(name = "source_reference_type", nullable = false)
    private String sourceReferenceType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LedgerTransactionStatus status;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // IMMUTABILITY INVARIANT: CascadeType.PERSIST only — ledger entries must never be
    // deleted or orphan-removed through the JPA session. The DB trigger
    // trg_immutable_ledger_entries protects against direct SQL DELETE, and
    // trg_immutable_ledger_transactions (V13) protects ledger_transactions.
    // DO NOT change to CascadeType.ALL or add orphanRemoval=true.
    @OneToMany(mappedBy = "ledgerTransaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntryEntity> entries = new ArrayList<>();

    protected LedgerTransactionEntity() {}

    public LedgerTransactionEntity(LedgerTransactionType transactionType, UUID sourceReferenceId, String sourceReferenceType, String currency, String description) {
        this.transactionType = transactionType;
        this.sourceReferenceId = sourceReferenceId;
        this.sourceReferenceType = sourceReferenceType;
        this.currency = currency;
        this.description = description;
        this.status = LedgerTransactionStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public void addEntry(LedgerEntryEntity entry) {
        entries.add(entry);
        entry.setLedgerTransaction(this);
    }

    public void post() {
        if (this.status != LedgerTransactionStatus.PENDING) {
            throw new IllegalStateException("Transaction must be PENDING to post");
        }
        
        long debitSum = 0;
        long creditSum = 0;

        for (LedgerEntryEntity entry : entries) {
            if (!entry.getCurrency().equals(this.currency)) {
                throw new IllegalStateException("Ledger entry currency does not match transaction currency");
            }
            if (entry.getAmountMinor() <= 0) {
                throw new IllegalStateException("Ledger entry amount must be positive");
            }

            if (entry.getDirection() == LedgerEntryDirection.DEBIT) {
                debitSum += entry.getAmountMinor();
            } else if (entry.getDirection() == LedgerEntryDirection.CREDIT) {
                creditSum += entry.getAmountMinor();
            }
        }

        if (debitSum != creditSum) {
            throw new IllegalStateException("Transaction is unbalanced: debit sum = " + debitSum + ", credit sum = " + creditSum);
        }

        this.status = LedgerTransactionStatus.POSTED;
        this.postedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public LedgerTransactionType getTransactionType() { return transactionType; }
    public UUID getSourceReferenceId() { return sourceReferenceId; }
    public String getSourceReferenceType() { return sourceReferenceType; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public LedgerTransactionStatus getStatus() { return status; }
    public Instant getPostedAt() { return postedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<LedgerEntryEntity> getEntries() { return entries; }
}
