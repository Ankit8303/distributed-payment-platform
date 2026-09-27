package com.paymentledger.payout.domain;

public enum PayoutStatus {
    REQUESTED,
    PROCESSING,
    SETTLED,
    FAILED,
    PENDING_RECONCILIATION
}
