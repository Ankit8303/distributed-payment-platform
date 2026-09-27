package com.paymentledger.admin.api.dto;

import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;

import java.time.Instant;
import java.util.UUID;

public record RefundAdminResponse(
        UUID id,
        UUID paymentId,
        long amountMinor,
        String currency,
        RefundStatus status,
        String reason,
        String providerReference,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundAdminResponse fromEntity(RefundEntity refund) {
        return new RefundAdminResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getAmountMinor(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getReason(),
                refund.getProviderReference(),
                refund.getCreatedAt(),
                refund.getUpdatedAt()
        );
    }
}
