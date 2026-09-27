package com.paymentledger.payment.service;

import com.paymentledger.payment.domain.PaymentEntity;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class FakePaymentProvider implements PaymentProvider {

    @Override
    public PaymentProviderResponse authorize(PaymentEntity payment, String paymentMethodToken) {
        if ("tok_timeout".equals(paymentMethodToken)) {
            return PaymentProviderResponse.timeout();
        }
        if ("tok_decline".equals(paymentMethodToken)) {
            return PaymentProviderResponse.failure("INSUFFICIENT_FUNDS_AT_ISSUER");
        }
        return PaymentProviderResponse.success("auth_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public PaymentProviderResponse capture(PaymentEntity payment) {
        if ("auth_timeout".equals(payment.getProviderReference())) {
            return PaymentProviderResponse.timeout();
        }
        if ("auth_decline".equals(payment.getProviderReference())) {
            return PaymentProviderResponse.failure("CAPTURE_FAILED");
        }
        return PaymentProviderResponse.success("cap_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public PaymentProviderResponse refund(PaymentEntity payment, long amountMinor, String reason) {
        if ("ref_timeout".equals(reason)) {
            return PaymentProviderResponse.timeout();
        }
        if ("ref_decline".equals(reason)) {
            return PaymentProviderResponse.failure("REFUND_DECLINED");
        }
        return PaymentProviderResponse.success("ref_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public PaymentProviderResponse payout(UUID accountId, long amountMinor, String currency) {
        if (amountMinor == 999999L) {
            return PaymentProviderResponse.timeout();
        }
        if (amountMinor == 888888L) {
            return PaymentProviderResponse.failure("PAYOUT_DECLINED");
        }
        return PaymentProviderResponse.success("payout_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private final java.util.Map<UUID, ProviderOperationStatus> operationStatusOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, ProviderOperationStatus> referenceStatusOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    public void registerOperationStatus(UUID operationId, ProviderOperationStatus status) {
        operationStatusOverrides.put(operationId, status);
    }

    public void registerProviderReferenceStatus(String providerReference, ProviderOperationStatus status) {
        referenceStatusOverrides.put(providerReference, status);
    }

    public void clearOverrides() {
        operationStatusOverrides.clear();
        referenceStatusOverrides.clear();
    }

    @Override
    public ProviderOperationStatus queryOperationStatus(String operationType, UUID operationId, String providerReference) {
        if (operationId != null && operationStatusOverrides.containsKey(operationId)) {
            return operationStatusOverrides.get(operationId);
        }
        if (providerReference != null && referenceStatusOverrides.containsKey(providerReference)) {
            return referenceStatusOverrides.get(providerReference);
        }

        // Deterministic convention based on provider reference or known prefix
        if (providerReference != null) {
            if (providerReference.contains("decline") || providerReference.contains("fail") || providerReference.startsWith("fail_")) {
                return ProviderOperationStatus.failure("DECLINED_BY_ISSUER", "Provider reported terminal failure");
            }
            if (providerReference.contains("unknown") || providerReference.startsWith("unk_")) {
                return ProviderOperationStatus.unknown("Provider transaction status is indeterminate");
            }
            if (providerReference.contains("pending")) {
                return ProviderOperationStatus.pending();
            }
            return ProviderOperationStatus.success(providerReference);
        }

        return ProviderOperationStatus.success("prov_resolved_" + (operationId != null ? operationId.toString().substring(0, 8) : UUID.randomUUID().toString().substring(0, 8)));
    }
}
