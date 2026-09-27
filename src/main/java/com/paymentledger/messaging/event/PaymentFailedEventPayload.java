package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record PaymentFailedEventPayload(
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("payerAccountId") UUID payerAccountId,
        @JsonProperty("amountMinor") long amountMinor,
        @JsonProperty("currency") String currency,
        @JsonProperty("failureReason") String failureReason,
        @JsonProperty("failedAt") Instant failedAt
) {}
