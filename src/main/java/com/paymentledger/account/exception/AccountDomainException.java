package com.paymentledger.account.exception;

public class AccountDomainException extends RuntimeException {
    public AccountDomainException(String message) {
        super(message);
    }
}
