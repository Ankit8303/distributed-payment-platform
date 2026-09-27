package com.paymentledger.account.api.dto;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;

import java.time.Instant;
import java.util.UUID;

public class AccountResponse {
    private UUID accountId;
    private String accountNumber;
    private UUID ownerId;
    private AccountType accountType;
    private String currency;
    private AccountStatus status;
    private Instant createdAt;

    public AccountResponse() {
    }

    public AccountResponse(AccountEntity entity) {
        this.accountId = entity.getId();
        this.accountNumber = entity.getAccountNumber();
        this.ownerId = entity.getOwnerId();
        this.accountType = entity.getAccountType();
        this.currency = entity.getCurrency();
        this.status = entity.getStatus();
        this.createdAt = entity.getCreatedAt();
    }

    // Getters and Setters
    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
