package com.paymentledger.refund.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record ReversalResponse(
        @JsonProperty("reversalId") UUID reversalId,
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason,
        @JsonProperty("compensatingLedgerTransactionId") UUID compensatingLedgerTransactionId,
        @JsonProperty("failureReason") String failureReason,
        @JsonProperty("createdAt") Instant createdAt
) {}
