package com.paymentledger.payment.service;

public class ProviderOperationStatus {

    public enum Outcome {
        SUCCESS,
        FAILED,
        PENDING,
        UNKNOWN
    }

    private final Outcome outcome;
    private final String providerReference;
    private final String errorCode;
    private final String message;

    public ProviderOperationStatus(Outcome outcome, String providerReference, String errorCode, String message) {
        this.outcome = outcome;
        this.providerReference = providerReference;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static ProviderOperationStatus success(String providerReference) {
        return new ProviderOperationStatus(Outcome.SUCCESS, providerReference, null, null);
    }

    public static ProviderOperationStatus failure(String errorCode, String message) {
        return new ProviderOperationStatus(Outcome.FAILED, null, errorCode, message);
    }

    public static ProviderOperationStatus pending() {
        return new ProviderOperationStatus(Outcome.PENDING, null, null, "Operation pending at provider");
    }

    public static ProviderOperationStatus unknown(String message) {
        return new ProviderOperationStatus(Outcome.UNKNOWN, null, "UNKNOWN_STATUS", message);
    }

    public Outcome getOutcome() { return outcome; }
    public String getProviderReference() { return providerReference; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
}
