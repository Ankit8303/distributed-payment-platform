package com.paymentledger.messaging.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.exception.UnsupportedEventVersionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Idempotent audit consumer for payment events.
 * Demonstrates at-least-once delivery handling with durable PostgreSQL deduplication
 * and atomic database transaction for consumer side-effects.
 */
@Component
public class PaymentEventAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventAuditConsumer.class);
    public static final String CONSUMER_GROUP = "payment-audit-group";

    private final ObjectMapper objectMapper;
    private final PaymentEventAuditService auditService;

    // In-memory counters for test verification and observability metrics
    private final AtomicInteger processedEventCount = new AtomicInteger(0);
    private final AtomicInteger duplicateSkippedCount = new AtomicInteger(0);
    private final ConcurrentHashMap<UUID, String> processedEvents = new ConcurrentHashMap<>();

    public PaymentEventAuditConsumer(ObjectMapper objectMapper, PaymentEventAuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @KafkaListener(topics = TopicNames.PAYMENT_EVENTS, groupId = CONSUMER_GROUP)
    public void onPaymentEvent(ConsumerRecord<String, String> record,
                               @Header(value = "X-Correlation-ID", required = false) String headerCorrelationId) throws Exception {

        String payload = record.value();

        // 1. Establish correlation context in MDC
        if (headerCorrelationId != null && !headerCorrelationId.isBlank()) {
            MDC.put("correlationId", headerCorrelationId);
        }

        try {
            // 2. Deserialize envelope
            EventEnvelope<JsonNode> envelope = objectMapper.readValue(payload, new TypeReference<EventEnvelope<JsonNode>>() {});

            if (envelope.correlationId() != null) {
                MDC.put("correlationId", envelope.correlationId().toString());
            }

            log.info("Consumer {} received event {} [{}] from topic {} [partition={}, offset={}]",
                    CONSUMER_GROUP, envelope.eventId(), envelope.eventType(), record.topic(), record.partition(), record.offset());

            // 3. Version validation (non-retryable; routed to DLQ if unsupported)
            if (!EventEnvelope.CURRENT_SCHEMA_VERSION.equals(envelope.schemaVersion())) {
                log.error("Rejecting event {} with unsupported schemaVersion: {}", envelope.eventId(), envelope.schemaVersion());
                throw new UnsupportedEventVersionException(envelope.schemaVersion());
            }

            // 4. Atomically record deduplication marker and perform persistent consumer side-effect
            boolean isNew = auditService.processAndAudit(CONSUMER_GROUP, envelope, payload);
            if (!isNew) {
                log.info("Event {} was already processed by group {}. Deduplication gate prevented duplicate side-effect.",
                        envelope.eventId(), CONSUMER_GROUP);
                duplicateSkippedCount.incrementAndGet();
                return;
            }

            // 5. Update observability metrics
            processedEvents.put(envelope.eventId(), envelope.eventType());
            processedEventCount.incrementAndGet();
            log.info("Successfully audited payment event: {} [{}] for aggregate {}",
                    envelope.eventId(), envelope.eventType(), envelope.aggregateId());

        } finally {
            MDC.remove("correlationId");
        }
    }

    public int getProcessedEventCount() {
        return processedEventCount.get();
    }

    public int getDuplicateSkippedCount() {
        return duplicateSkippedCount.get();
    }

    public ConcurrentHashMap<UUID, String> getProcessedEvents() {
        return processedEvents;
    }

    public void reset() {
        processedEventCount.set(0);
        duplicateSkippedCount.set(0);
        processedEvents.clear();
    }
}
