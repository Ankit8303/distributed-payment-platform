package com.paymentledger.payment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class PaymentCreateRequest {

    @NotNull(message = "Payee account ID is required")
    private UUID payeeAccountId;

    @Min(value = 1, message = "Amount must be strictly positive")
    private long amountMinor;

    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    private String currency;

    @NotBlank(message = "Payment method token is required")
    private String paymentMethodToken;

    public UUID getPayeeAccountId() { return payeeAccountId; }
    public void setPayeeAccountId(UUID payeeAccountId) { this.payeeAccountId = payeeAccountId; }

    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getPaymentMethodToken() { return paymentMethodToken; }
    public void setPaymentMethodToken(String paymentMethodToken) { this.paymentMethodToken = paymentMethodToken; }
}
