package com.paymentledger.payout.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record PayoutResponse(
        @JsonProperty("payoutId") UUID payoutId,
        @JsonProperty("accountId") UUID accountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("status") String status,
        @JsonProperty("providerReference") String providerReference,
        @JsonProperty("compensatingLedgerTransactionId") UUID compensatingLedgerTransactionId,
        @JsonProperty("failureReason") String failureReason,
        @JsonProperty("createdAt") Instant createdAt
) {}
