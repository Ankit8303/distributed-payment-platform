package com.paymentledger.auth.exception;

/**
 * Thrown when a refresh token is invalid, expired, or revoked.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
