package com.paymentledger.notification.provider;

import java.util.UUID;

public interface WebhookProvider {

    WebhookResult sendWebhook(String targetUrl, String eventType, UUID eventId, String payload, String correlationId);
}
