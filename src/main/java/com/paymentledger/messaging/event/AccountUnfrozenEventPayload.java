package com.paymentledger.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record AccountUnfrozenEventPayload(
        @JsonProperty("accountId") UUID accountId,
        @JsonProperty("ownerId") UUID ownerId,
        @JsonProperty("reason") String reason,
        @JsonProperty("unfrozenAt") Instant unfrozenAt
) {}
