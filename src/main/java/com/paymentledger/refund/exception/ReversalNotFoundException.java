package com.paymentledger.refund.exception;

public class ReversalNotFoundException extends RuntimeException {
    public ReversalNotFoundException(String message) {
        super(message);
    }
}
