package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record FinancialAdjustmentPostedEventPayload(
        @JsonProperty("adjustmentId") UUID adjustmentId,
        @JsonProperty("sourceAccountId") UUID sourceAccountId,
        @JsonProperty("targetAccountId") UUID targetAccountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("operatorId") UUID operatorId,
        @JsonProperty("ledgerTransactionId") UUID ledgerTransactionId,
        @JsonProperty("reason") String reason,
        @JsonProperty("postedAt") Instant postedAt
) {}
