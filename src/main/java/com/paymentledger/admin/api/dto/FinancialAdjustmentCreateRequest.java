package com.paymentledger.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record FinancialAdjustmentCreateRequest(
        @NotNull(message = "Source account ID is required")
        @JsonProperty("sourceAccountId")
        UUID sourceAccountId,

        @NotNull(message = "Target account ID is required")
        @JsonProperty("targetAccountId")
        UUID targetAccountId,

        @NotNull(message = "Amount is required")
        @Min(value = 1, message = "Amount must be strictly positive")
        @JsonProperty("amountMinor")
        Long amountMinor,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be 3-letter ISO code")
        @JsonProperty("currency")
        String currency,

        @NotBlank(message = "Reason is strictly required for administrative adjustments")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        @JsonProperty("reason")
        String reason
) {}
