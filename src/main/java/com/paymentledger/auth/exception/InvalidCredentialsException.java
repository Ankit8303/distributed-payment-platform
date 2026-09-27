package com.paymentledger.auth.exception;

/**
 * Thrown when login credentials are invalid.
 * The message intentionally avoids disclosing whether the email or password was incorrect
 * to prevent user enumeration.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
