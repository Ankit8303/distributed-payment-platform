package com.paymentledger.notification.provider;

import com.paymentledger.notification.security.SsrfBlockedException;
import com.paymentledger.notification.security.WebhookSecurityValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakeWebhookProvider implements WebhookProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeWebhookProvider.class);

    public record SentWebhook(String targetUrl, String eventType, UUID eventId, String payload, String correlationId, String providerRef) {}

    private final WebhookSecurityValidator securityValidator;
    private final Map<String, WebhookResult> urlOverrides = new ConcurrentHashMap<>();
    private final List<SentWebhook> sentWebhooks = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCallCount = new AtomicInteger(0);

    public FakeWebhookProvider(WebhookSecurityValidator securityValidator) {
        this.securityValidator = securityValidator;
    }

    @Override
    public WebhookResult sendWebhook(String targetUrl, String eventType, UUID eventId, String payload, String correlationId) {
        sendCallCount.incrementAndGet();
        log.info("FakeWebhookProvider: Sending webhook to {} for event {} [{}]", targetUrl, eventId, eventType);

        // 1. Enforce SSRF validation
        try {
            securityValidator.validateUrl(targetUrl);
        } catch (SsrfBlockedException e) {
            log.warn("FakeWebhookProvider: Target URL {} blocked by SSRF policy: {}", targetUrl, e.getMessage());
            return WebhookResult.failure(ProviderErrorClassification.SSRF_BLOCKED, 400, "SSRF validation failed: " + e.getMessage());
        }

        // 2. Check overrides
        if (urlOverrides.containsKey(targetUrl)) {
            WebhookResult override = urlOverrides.get(targetUrl);
            log.info("FakeWebhookProvider: Applying override for targetUrl {}: success={}", targetUrl, override.success());
            return override;
        }

        String providerRef = "webhook_ref_" + UUID.randomUUID();
        sentWebhooks.add(new SentWebhook(targetUrl, eventType, eventId, payload, correlationId, providerRef));
        return WebhookResult.success(providerRef, 200);
    }

    public void registerOverride(String targetUrl, WebhookResult result) {
        urlOverrides.put(targetUrl, result);
    }

    public void clearOverrides() {
        urlOverrides.clear();
        sentWebhooks.clear();
        sendCallCount.set(0);
    }

    public List<SentWebhook> getSentWebhooks() {
        return Collections.unmodifiableList(sentWebhooks);
    }

    public int getSendCallCount() {
        return sendCallCount.get();
    }
}
