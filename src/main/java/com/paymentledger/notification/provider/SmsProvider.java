package com.paymentledger.notification.provider;

public interface SmsProvider {

    SmsResult sendSms(String recipient, String message, String correlationId);
}
