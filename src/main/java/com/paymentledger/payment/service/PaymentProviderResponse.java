package com.paymentledger.payment.service;

public class PaymentProviderResponse {
    private final boolean success;
    private final String providerReference;
    private final String errorCode;
    private final boolean timeout;

    private PaymentProviderResponse(boolean success, String providerReference, String errorCode, boolean timeout) {
        this.success = success;
        this.providerReference = providerReference;
        this.errorCode = errorCode;
        this.timeout = timeout;
    }

    public static PaymentProviderResponse success(String providerReference) {
        return new PaymentProviderResponse(true, providerReference, null, false);
    }

    public static PaymentProviderResponse failure(String errorCode) {
        return new PaymentProviderResponse(false, null, errorCode, false);
    }

    public static PaymentProviderResponse timeout() {
        return new PaymentProviderResponse(false, null, null, true);
    }

    public boolean isSuccess() { return success; }
    public String getProviderReference() { return providerReference; }
    public String getErrorCode() { return errorCode; }
    public boolean isTimeout() { return timeout; }
}
