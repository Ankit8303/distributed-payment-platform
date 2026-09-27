package com.paymentledger.admin.api.dto;

import com.paymentledger.ledger.domain.LedgerTransactionEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionAdminResponse(
        UUID id,
        UUID sourceReferenceId,
        String sourceReferenceType,
        String description,
        Instant createdAt,
        List<LedgerEntryAdminResponse> entries
) {
    public static LedgerTransactionAdminResponse fromEntity(LedgerTransactionEntity tx, List<LedgerEntryAdminResponse> entries) {
        return new LedgerTransactionAdminResponse(
                tx.getId(),
                tx.getSourceReferenceId(),
                tx.getSourceReferenceType(),
                tx.getDescription(),
                tx.getCreatedAt(),
                entries != null ? entries : List.of()
        );
    }
}
