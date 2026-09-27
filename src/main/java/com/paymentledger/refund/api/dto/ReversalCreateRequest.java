package com.paymentledger.refund.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalCreateRequest(
        @NotBlank(message = "Reason is strictly required for reversal")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        @JsonProperty("reason")
        String reason
) {}
