package com.paymentledger.admin.api.dto;

import com.paymentledger.ledger.domain.LedgerEntryEntity;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryAdminResponse(
        UUID id,
        UUID accountId,
        String direction,
        long amountMinor,
        String currency,
        long sequenceNumber,
        Instant createdAt
) {
    public static LedgerEntryAdminResponse fromEntity(LedgerEntryEntity entry) {
        return new LedgerEntryAdminResponse(
                entry.getId(),
                entry.getAccountId(),
                entry.getDirection().name(),
                entry.getAmountMinor(),
                entry.getCurrency(),
                entry.getSequenceNumber(),
                entry.getCreatedAt()
        );
    }
}
