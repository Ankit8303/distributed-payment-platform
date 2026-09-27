package com.paymentledger.refund.domain;

public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    SETTLED,
    FAILED,
    PENDING_RECONCILIATION
}
