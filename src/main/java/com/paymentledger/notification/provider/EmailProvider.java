package com.paymentledger.notification.provider;

public interface EmailProvider {

    EmailResult sendEmail(String recipient, String subject, String body, String correlationId);
}
