package com.paymentledger.admin.api.dto;

import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.domain.PayoutStatus;

import java.time.Instant;
import java.util.UUID;

public record PayoutAdminResponse(
        UUID id,
        UUID accountId,
        long amountMinor,
        String currency,
        PayoutStatus status,
        String providerReference,
        Instant createdAt,
        Instant updatedAt
) {
    public static PayoutAdminResponse fromEntity(PayoutEntity payout) {
        return new PayoutAdminResponse(
                payout.getId(),
                payout.getAccountId(),
                payout.getAmountMinor(),
                payout.getCurrency(),
                payout.getStatus(),
                payout.getProviderReference(),
                payout.getCreatedAt(),
                payout.getUpdatedAt()
        );
    }
}
