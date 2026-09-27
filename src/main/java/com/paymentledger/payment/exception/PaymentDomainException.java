package com.paymentledger.payment.exception;

public class PaymentDomainException extends RuntimeException {
    public PaymentDomainException(String message) {
        super(message);
    }
}
