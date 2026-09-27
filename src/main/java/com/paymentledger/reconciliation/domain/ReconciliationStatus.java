package com.paymentledger.reconciliation.domain;

public enum ReconciliationStatus {
    OPEN,
    IN_PROGRESS,
    RETRY_REQUIRED,
    RESOLVED,
    MANUAL_REVIEW
}
