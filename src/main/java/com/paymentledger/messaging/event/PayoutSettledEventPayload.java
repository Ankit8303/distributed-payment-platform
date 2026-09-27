package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record PayoutSettledEventPayload(
        @JsonProperty("payoutId") UUID payoutId,
        @JsonProperty("accountId") UUID accountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("ledgerTransactionId") UUID ledgerTransactionId,
        @JsonProperty("providerReference") String providerReference,
        @JsonProperty("settledAt") Instant settledAt
) {}
