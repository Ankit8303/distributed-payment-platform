package com.paymentledger.payment.service;

import com.paymentledger.payment.domain.PaymentEntity;

import java.util.UUID;

public interface PaymentProvider {
    PaymentProviderResponse authorize(PaymentEntity payment, String paymentMethodToken);
    PaymentProviderResponse capture(PaymentEntity payment);
    PaymentProviderResponse refund(PaymentEntity payment, long amountMinor, String reason);
    PaymentProviderResponse payout(UUID accountId, long amountMinor, String currency);
    ProviderOperationStatus queryOperationStatus(String operationType, UUID operationId, String providerReference);
}
