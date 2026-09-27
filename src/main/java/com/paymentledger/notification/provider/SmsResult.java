package com.paymentledger.notification.provider;

public record SmsResult(
        boolean success,
        String providerReference,
        ProviderErrorClassification errorClassification,
        Integer httpStatusCode,
        String errorMessage
) {
    public static SmsResult success(String providerRef) {
        return new SmsResult(true, providerRef, ProviderErrorClassification.NONE, 200, null);
    }

    public static SmsResult failure(ProviderErrorClassification classification, Integer statusCode, String error) {
        return new SmsResult(false, null, classification, statusCode, error);
    }
}
