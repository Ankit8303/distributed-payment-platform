package com.paymentledger.notification.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakeEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeEmailProvider.class);

    public record SentEmail(String recipient, String subject, String body, String correlationId, String providerRef) {}

    private final Map<String, EmailResult> recipientOverrides = new ConcurrentHashMap<>();
    private final List<SentEmail> sentEmails = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCallCount = new AtomicInteger(0);

    @Override
    public EmailResult sendEmail(String recipient, String subject, String body, String correlationId) {
        sendCallCount.incrementAndGet();
        log.info("FakeEmailProvider: Sending email to {} [subject: {}]", recipient, subject);

        if (recipientOverrides.containsKey(recipient)) {
            EmailResult override = recipientOverrides.get(recipient);
            log.info("FakeEmailProvider: Applying override for recipient {}: success={}", recipient, override.success());
            return override;
        }

        String providerRef = "email_ref_" + UUID.randomUUID();
        sentEmails.add(new SentEmail(recipient, subject, body, correlationId, providerRef));
        return EmailResult.success(providerRef);
    }

    public void registerOverride(String recipient, EmailResult result) {
        recipientOverrides.put(recipient, result);
    }

    public void clearOverrides() {
        recipientOverrides.clear();
        sentEmails.clear();
        sendCallCount.set(0);
    }

    public List<SentEmail> getSentEmails() {
        return Collections.unmodifiableList(sentEmails);
    }

    public int getSendCallCount() {
        return sendCallCount.get();
    }
}
