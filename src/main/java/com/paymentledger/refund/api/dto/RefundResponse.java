package com.paymentledger.refund.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        @JsonProperty("refundId") UUID refundId,
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason,
        @JsonProperty("providerReference") String providerReference,
        @JsonProperty("compensatingLedgerTransactionId") UUID compensatingLedgerTransactionId,
        @JsonProperty("failureReason") String failureReason,
        @JsonProperty("createdAt") Instant createdAt
) {}
