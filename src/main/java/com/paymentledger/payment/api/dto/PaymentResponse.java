package com.paymentledger.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private UUID paymentId;
    private String idempotencyKey;
    private UUID payerAccountId;
    private UUID payeeAccountId;
    private Long amountMinor;
    private Long feeAmountMinor;
    private String currency;
    private String status;
    private String providerReference;
    private String correlationId;
    private Instant createdAt;
    
    // For pending reconciliation
    private String message;
    private String pollUrl;

    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public UUID getPayerAccountId() { return payerAccountId; }
    public void setPayerAccountId(UUID payerAccountId) { this.payerAccountId = payerAccountId; }

    public UUID getPayeeAccountId() { return payeeAccountId; }
    public void setPayeeAccountId(UUID payeeAccountId) { this.payeeAccountId = payeeAccountId; }

    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }

    public Long getFeeAmountMinor() { return feeAmountMinor; }
    public void setFeeAmountMinor(Long feeAmountMinor) { this.feeAmountMinor = feeAmountMinor; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getPollUrl() { return pollUrl; }
    public void setPollUrl(String pollUrl) { this.pollUrl = pollUrl; }
}
