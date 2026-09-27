package com.paymentledger.notification.provider;

public record EmailResult(
        boolean success,
        String providerReference,
        ProviderErrorClassification errorClassification,
        Integer httpStatusCode,
        String errorMessage
) {
    public static EmailResult success(String providerRef) {
        return new EmailResult(true, providerRef, ProviderErrorClassification.NONE, 200, null);
    }

    public static EmailResult failure(ProviderErrorClassification classification, Integer statusCode, String error) {
        return new EmailResult(false, null, classification, statusCode, error);
    }
}
