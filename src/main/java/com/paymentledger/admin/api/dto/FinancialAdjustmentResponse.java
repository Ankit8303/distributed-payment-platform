package com.paymentledger.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record FinancialAdjustmentResponse(
        @JsonProperty("adjustmentId") UUID adjustmentId,
        @JsonProperty("sourceAccountId") UUID sourceAccountId,
        @JsonProperty("targetAccountId") UUID targetAccountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("reason") String reason,
        @JsonProperty("operatorId") UUID operatorId,
        @JsonProperty("compensatingLedgerTransactionId") UUID compensatingLedgerTransactionId,
        @JsonProperty("createdAt") Instant createdAt
) {}
