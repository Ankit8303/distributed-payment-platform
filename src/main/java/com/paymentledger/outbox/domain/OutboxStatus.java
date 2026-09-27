package com.paymentledger.outbox.domain;

/**
 * Lifecycle states of an outbox event.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
