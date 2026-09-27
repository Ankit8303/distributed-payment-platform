package com.paymentledger.admin.api.dto;

import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentAdminResponse(
        UUID id,
        UUID payerAccountId,
        UUID payeeAccountId,
        long amountMinor,
        long feeMinor,
        String currency,
        PaymentStatus status,
        String providerReference,
        String idempotencyKey,
        String idempotencyScope,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentAdminResponse fromEntity(PaymentEntity payment) {
        return new PaymentAdminResponse(
                payment.getId(),
                payment.getPayerAccountId(),
                payment.getPayeeAccountId(),
                payment.getAmountMinor(),
                payment.getFeeAmountMinor(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getProviderReference(),
                payment.getIdempotencyKey(),
                payment.getIdempotencyScope(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
