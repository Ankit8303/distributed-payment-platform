package com.paymentledger.notification.provider;

public enum ProviderErrorClassification {
    NONE,
    TRANSIENT,
    PERMANENT,
    RATE_LIMITED,
    INVALID_RECIPIENT,
    AUTHENTICATION_FAILURE,
    SSRF_BLOCKED,
    UNKNOWN;

    public boolean isRetryable() {
        return this == TRANSIENT || this == RATE_LIMITED || this == UNKNOWN;
    }
}
