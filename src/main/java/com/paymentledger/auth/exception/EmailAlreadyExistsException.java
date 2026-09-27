package com.paymentledger.auth.exception;

/**
 * Thrown when a registration attempt uses an email that already exists.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("An account with email '" + email + "' already exists");
    }
}
