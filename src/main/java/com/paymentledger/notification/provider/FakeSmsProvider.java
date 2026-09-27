package com.paymentledger.notification.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakeSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeSmsProvider.class);

    public record SentSms(String recipient, String message, String correlationId, String providerRef) {}

    private final Map<String, SmsResult> recipientOverrides = new ConcurrentHashMap<>();
    private final List<SentSms> sentSmsList = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCallCount = new AtomicInteger(0);

    @Override
    public SmsResult sendSms(String recipient, String message, String correlationId) {
        sendCallCount.incrementAndGet();
        log.info("FakeSmsProvider: Sending SMS to {}", recipient);

        if (recipientOverrides.containsKey(recipient)) {
            SmsResult override = recipientOverrides.get(recipient);
            log.info("FakeSmsProvider: Applying override for recipient {}: success={}", recipient, override.success());
            return override;
        }

        String providerRef = "sms_ref_" + UUID.randomUUID();
        sentSmsList.add(new SentSms(recipient, message, correlationId, providerRef));
        return SmsResult.success(providerRef);
    }

    public void registerOverride(String recipient, SmsResult result) {
        recipientOverrides.put(recipient, result);
    }

    public void clearOverrides() {
        recipientOverrides.clear();
        sentSmsList.clear();
        sendCallCount.set(0);
    }

    public List<SentSms> getSentSmsList() {
        return Collections.unmodifiableList(sentSmsList);
    }

    public int getSendCallCount() {
        return sendCallCount.get();
    }
}
