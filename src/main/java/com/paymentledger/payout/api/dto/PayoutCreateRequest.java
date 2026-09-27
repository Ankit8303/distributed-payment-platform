package com.paymentledger.payout.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PayoutCreateRequest(
        @NotNull(message = "Account ID is required")
        @JsonProperty("accountId")
        UUID accountId,

        @NotNull(message = "Amount is required")
        @Min(value = 1, message = "Amount must be strictly positive")
        @JsonProperty("amountMinor")
        Long amountMinor,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be 3-letter ISO code")
        @JsonProperty("currency")
        String currency
) {}
