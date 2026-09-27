package com.paymentledger.notification.domain;

public enum NotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    RETRY_REQUIRED,
    FAILED,
    SUPPRESSED
}
