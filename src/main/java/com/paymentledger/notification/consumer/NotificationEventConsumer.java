package com.paymentledger.notification.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.consumer.ConsumerDeduplicationService;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.exception.UnsupportedEventVersionException;
import com.paymentledger.notification.domain.NotificationEntity;
import com.paymentledger.notification.service.NotificationService;
import com.paymentledger.notification.worker.NotificationWorker;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);
    public static final String CONSUMER_GROUP = "notification-orchestration-group";

    private final ObjectMapper objectMapper;
    private final ConsumerDeduplicationService deduplicationService;
    private final NotificationService notificationService;
    private final NotificationWorker notificationWorker;

    private final AtomicInteger consumedCount = new AtomicInteger(0);
    private final AtomicInteger duplicateSkippedCount = new AtomicInteger(0);

    public NotificationEventConsumer(ObjectMapper objectMapper,
                                     ConsumerDeduplicationService deduplicationService,
                                     NotificationService notificationService,
                                     NotificationWorker notificationWorker) {
        this.objectMapper = objectMapper;
        this.deduplicationService = deduplicationService;
        this.notificationService = notificationService;
        this.notificationWorker = notificationWorker;
    }

    @KafkaListener(topics = {TopicNames.PAYMENT_EVENTS, TopicNames.REFUND_EVENTS, TopicNames.ACCOUNT_EVENTS},
                   groupId = CONSUMER_GROUP)
    public void onDomainEvent(ConsumerRecord<String, String> record,
                              @Header(value = "X-Correlation-ID", required = false) String headerCorrelationId) throws Exception {

        String payload = record.value();

        if (headerCorrelationId != null && !headerCorrelationId.isBlank()) {
            MDC.put("correlationId", headerCorrelationId);
        }

        try {
            // 1. Deserialize envelope
            EventEnvelope<JsonNode> envelope = objectMapper.readValue(payload, new TypeReference<EventEnvelope<JsonNode>>() {});

            if (envelope.correlationId() != null) {
                MDC.put("correlationId", envelope.correlationId().toString());
            }

            log.info("Notification consumer received event {} [{}] from topic {} [partition={}, offset={}]",
                    envelope.eventId(), envelope.eventType(), record.topic(), record.partition(), record.offset());

            // 2. Validate schema version
            if (!EventEnvelope.CURRENT_SCHEMA_VERSION.equals(envelope.schemaVersion())) {
                log.error("Rejecting event {} with unsupported schemaVersion: {}", envelope.eventId(), envelope.schemaVersion());
                throw new UnsupportedEventVersionException(envelope.schemaVersion());
            }

            // 3. Atomically check and record deduplication marker in PostgreSQL
            boolean isNew = deduplicationService.tryConsume(CONSUMER_GROUP, envelope.eventId(), envelope.eventType());
            if (!isNew) {
                log.info("Duplicate event detected by notification consumer: eventId={} [{}]. Skipping.",
                        envelope.eventId(), envelope.eventType());
                duplicateSkippedCount.incrementAndGet();
                return;
            }

            // 4. Ingest event and create notification records
            List<NotificationEntity> created = notificationService.ingestEvent(envelope);
            consumedCount.incrementAndGet();

            // 5. Trigger immediate delivery attempt for responsiveness
            for (NotificationEntity notif : created) {
                notificationService.deliverNotification(notif.getId(), notificationWorker.getWorkerId());
            }

        } finally {
            MDC.remove("correlationId");
        }
    }

    public int getConsumedCount() {
        return consumedCount.get();
    }

    public int getDuplicateSkippedCount() {
        return duplicateSkippedCount.get();
    }

    public void reset() {
        consumedCount.set(0);
        duplicateSkippedCount.set(0);
    }
}
