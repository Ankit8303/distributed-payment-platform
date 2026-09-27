package com.paymentledger.shared.redis;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Disposable, non-authoritative read DTO for account caching in Redis.
 * <p>
 * ARCHITECTURAL RULE:
 * This cached DTO represents a temporary point-in-time snapshot.
 * PostgreSQL remains the sole authoritative source of truth.
 * Under no circumstances may this cached DTO be used for financial debit decisions,
 * double-entry ledger postings, or settlement validation.
 */
public class CachedAccountDto implements Serializable {

    private UUID id;
    private String accountNumber;
    private UUID ownerId;
    private AccountType accountType;
    private String currency;
    private AccountStatus status;
    private long materializedBalanceMinor;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public CachedAccountDto() {
    }

    public CachedAccountDto(AccountEntity entity) {
        this.id = entity.getId();
        this.accountNumber = entity.getAccountNumber();
        this.ownerId = entity.getOwnerId();
        this.accountType = entity.getAccountType();
        this.currency = entity.getCurrency();
        this.status = entity.getStatus();
        this.materializedBalanceMinor = entity.getMaterializedBalanceMinor();
        this.version = entity.getVersion();
        this.createdAt = entity.getCreatedAt();
        this.updatedAt = entity.getUpdatedAt();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public long getMaterializedBalanceMinor() {
        return materializedBalanceMinor;
    }

    public void setMaterializedBalanceMinor(long materializedBalanceMinor) {
        this.materializedBalanceMinor = materializedBalanceMinor;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
