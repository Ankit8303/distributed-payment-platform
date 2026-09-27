package com.paymentledger.account.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to the {@code accounts} table.
 */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 50)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private AccountStatus status = AccountStatus.PENDING_VERIFICATION;

    @Column(name = "materialized_balance_minor", nullable = false)
    private long materializedBalanceMinor = 0L;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    public AccountEntity(String accountNumber, UUID ownerId, AccountType accountType, String currency, AccountStatus status) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.accountType = accountType;
        this.currency = currency;
        this.status = status;
        this.materializedBalanceMinor = 0L;
    }

    /**
     * Factory method for non-authoritative read reconstruction from Redis cache.
     */
    public static AccountEntity fromCached(UUID id, String accountNumber, UUID ownerId,
                                          AccountType accountType, String currency,
                                          AccountStatus status, long materializedBalanceMinor,
                                          long version, Instant createdAt, Instant updatedAt) {
        AccountEntity entity = new AccountEntity();
        entity.id = id;
        entity.accountNumber = accountNumber;
        entity.ownerId = ownerId;
        entity.accountType = accountType;
        entity.currency = currency;
        entity.status = status;
        entity.materializedBalanceMinor = materializedBalanceMinor;
        entity.version = version;
        entity.createdAt = createdAt;
        entity.updatedAt = updatedAt;
        return entity;
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

    // Domain operations
    public void freeze() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot freeze a closed account");
        }
        if (this.status == AccountStatus.FROZEN) {
            throw new IllegalStateException("Account is already frozen");
        }
        this.status = AccountStatus.FROZEN;
    }

    public void unfreeze() {
        if (this.status != AccountStatus.FROZEN) {
            throw new IllegalStateException("Account is not frozen");
        }
        this.status = AccountStatus.ACTIVE;
    }

    public void close() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Account is already closed");
        }
        this.status = AccountStatus.CLOSED;
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getMaterializedBalanceMinor() {
        return materializedBalanceMinor;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void addBalanceMinor(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be positive");
        this.materializedBalanceMinor += amount;
    }

    public void subtractBalanceMinor(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be positive");
        this.materializedBalanceMinor -= amount;
    }
}
