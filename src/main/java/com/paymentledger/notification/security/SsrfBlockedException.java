package com.paymentledger.notification.security;

public class SsrfBlockedException extends RuntimeException {
    public SsrfBlockedException(String message) {
        super(message);
    }
}
