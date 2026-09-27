package com.paymentledger.shared.error;

public class IdempotencyConflictException extends RuntimeException {
    private final String idempotencyKey;

    public IdempotencyConflictException(String message, String idempotencyKey) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
