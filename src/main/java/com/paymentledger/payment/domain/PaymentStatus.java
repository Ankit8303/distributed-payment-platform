package com.paymentledger.payment.domain;

public enum PaymentStatus {
    CREATED,
    AUTHORIZING,
    AUTHORIZED,
    CAPTURING,
    SETTLED,
    DECLINED,
    FAILED,
    EXPIRED,
    PENDING_RECONCILIATION
}
