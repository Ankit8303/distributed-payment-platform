package com.paymentledger.refund.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RefundCreateRequest(
        @NotNull(message = "Amount is required")
        @Min(value = 1, message = "Amount must be strictly positive")
        @JsonProperty("amountMinor")
        Long amountMinor,

        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        @JsonProperty("reason")
        String reason
) {}
