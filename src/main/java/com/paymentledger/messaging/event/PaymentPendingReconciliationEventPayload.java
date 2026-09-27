package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record PaymentPendingReconciliationEventPayload(
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("payerAccountId") UUID payerAccountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("reason") String reason,
        @JsonProperty("occurredAt") Instant occurredAt
) {}
