package com.paymentledger.messaging.config;

public final class TopicNames {
    private TopicNames() {}

    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String PAYMENT_EVENTS_DLQ = "payment.events.dlq";
    public static final String ACCOUNT_EVENTS = "account.events";
    public static final String ACCOUNT_EVENTS_DLQ = "account.events.dlq";
    public static final String REFUND_EVENTS = "refund.events";
    public static final String REFUND_EVENTS_DLQ = "refund.events.dlq";
    public static final String NOTIFICATION_EVENTS = "notification.events";
    public static final String NOTIFICATION_EVENTS_DLQ = "notification.events.dlq";
}
