package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record ReversalSettledEventPayload(
        @JsonProperty("reversalId") UUID reversalId,
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("payerAccountId") UUID payerAccountId,
        @JsonProperty("payeeAccountId") UUID payeeAccountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("ledgerTransactionId") UUID ledgerTransactionId,
        @JsonProperty("reason") String reason,
        @JsonProperty("settledAt") Instant settledAt
) {}
