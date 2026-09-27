package com.paymentledger.admin.api.dto;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountAdminResponse(
        UUID id,
        String accountNumber,
        UUID ownerId,
        AccountType accountType,
        String currency,
        AccountStatus status,
        long materializedBalanceMinor,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountAdminResponse fromEntity(AccountEntity account) {
        return new AccountAdminResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getOwnerId(),
                account.getAccountType(),
                account.getCurrency(),
                account.getStatus(),
                account.getMaterializedBalanceMinor(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
