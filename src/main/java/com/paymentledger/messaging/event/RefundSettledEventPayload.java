package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record RefundSettledEventPayload(
        @JsonProperty("refundId") UUID refundId,
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("payerAccountId") UUID payerAccountId,
        @JsonProperty("payeeAccountId") UUID payeeAccountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("ledgerTransactionId") UUID ledgerTransactionId,
        @JsonProperty("providerReference") String providerReference,
        @JsonProperty("settledAt") Instant settledAt
) {}
