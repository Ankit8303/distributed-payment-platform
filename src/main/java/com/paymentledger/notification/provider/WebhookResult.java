package com.paymentledger.notification.provider;

import java.util.UUID;

public record WebhookResult(
        boolean success,
        String providerReference,
        ProviderErrorClassification errorClassification,
        Integer httpStatusCode,
        String errorMessage
) {
    public static WebhookResult success(String providerRef, int statusCode) {
        return new WebhookResult(true, providerRef, ProviderErrorClassification.NONE, statusCode, null);
    }

    public static WebhookResult failure(ProviderErrorClassification classification, Integer statusCode, String error) {
        return new WebhookResult(false, null, classification, statusCode, error);
    }
}
