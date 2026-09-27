package com.paymentledger.outbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.producer.EventPublisher;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.shared.metrics.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous relay worker that polls claimed outbox events from PostgreSQL
 * and publishes them to Apache Kafka with exponential backoff and lease recovery.
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxService outboxService;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final String workerId;

    /** Canonical, fail-safe metrics service — may be null in test contexts. */
    private final PlatformMetrics platformMetrics;

    @Value("${outbox.relay.batch-size:50}")
    private int batchSize = 50;

    @Value("${outbox.relay.lease-seconds:30}")
    private int leaseSeconds = 30;

    @Value("${outbox.relay.max-retries:5}")
    private int maxRetries = 5;

    @Value("${outbox.relay.scheduling.enabled:true}")
    private static volatile boolean schedulingEnabled = true;

    // Local in-memory counters retained for test introspection
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);

    public OutboxRelayScheduler(OutboxService outboxService,
                                EventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) PlatformMetrics platformMetrics) {
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.workerId = "relay-" + UUID.randomUUID().toString().substring(0, 8);
        this.platformMetrics = platformMetrics;
    }

    public static void setSchedulingEnabled(boolean enabled) {
        schedulingEnabled = enabled;
    }

    public static boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:200}")
    public void scheduleRelay() {
        if (!schedulingEnabled) {
            return;
        }
        relayPendingEvents();
    }

    @org.springframework.transaction.event.TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onOutboxEventCommitted(com.paymentledger.outbox.domain.OutboxEventCreatedEvent event) {
        if (!schedulingEnabled) {
            return;
        }
        relayPendingEvents();
    }

    /**
     * Polls, claims, and relays a batch of outbox events to Kafka.
     *
     * @return count of successfully published events
     */
    public int relayPendingEvents() {
        List<OutboxEventEntity> claimedBatch;
        try {
            claimedBatch = outboxService.claimBatch(workerId, batchSize, Duration.ofSeconds(leaseSeconds));
        } catch (Exception ex) {
            log.error("Failed to claim outbox batch for worker {}: {}", workerId, ex.getMessage());
            return 0;
        }

        if (claimedBatch.isEmpty()) {
            return 0;
        }

        // Update processing gauge
        try {
            if (platformMetrics != null) {
                platformMetrics.updateOutboxProcessingCount(claimedBatch.size());
            }
        } catch (Exception ex) {
            log.warn("Metric update failed (outbox.processing): {}", ex.getMessage());
        }

        log.debug("Worker {} claimed {} outbox events for publishing", workerId, claimedBatch.size());
        int publishedCount = 0;

        for (OutboxEventEntity event : claimedBatch) {
            boolean success = publishSingleEvent(event);
            if (success) {
                publishedCount++;
            }
        }

        // Reset processing gauge after batch completes
        try {
            if (platformMetrics != null) {
                platformMetrics.updateOutboxProcessingCount(0);
                platformMetrics.recordOutboxPublished(publishedCount, 0L);
                if (publishedCount < claimedBatch.size()) {
                    platformMetrics.recordOutboxFailed(claimedBatch.size() - publishedCount);
                }
            }
        } catch (Exception ex) {
            log.warn("Metric update failed (outbox batch complete): {}", ex.getMessage());
        }

        return publishedCount;
    }

    private boolean publishSingleEvent(OutboxEventEntity event) {
        if (event.getCorrelationId() != null) {
            MDC.put("correlationId", event.getCorrelationId().toString());
        }

        try {
            JsonNode payloadNode = objectMapper.readTree(event.getPayload());

            EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                    event.getId(),
                    event.getEventType(),
                    event.getCreatedAt(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getSchemaVersion(),
                    event.getCorrelationId(),
                    event.getCausationId(),
                    payloadNode
            );

            // Publish to Kafka (5 second timeout)
            eventPublisher.publish(event.getTopic(), event.getPartitionKey(), envelope)
                    .get(5, TimeUnit.SECONDS);

            outboxService.markPublished(event.getId());
            successCount.incrementAndGet();
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordKafkaConsumerRecord(event.getTopic(), event.getEventType());
                }
            } catch (Exception ex) {
                log.warn("Metric recording failed (kafka.consumer.records outbox relay): {}", ex.getMessage());
            }
            log.info("Relayed outbox event {} [{}] to topic {} (key={})",
                    event.getId(), event.getEventType(), event.getTopic(), event.getPartitionKey());
            return true;

        } catch (Exception ex) {
            failureCount.incrementAndGet();
            try {
                if (platformMetrics != null) {
                    platformMetrics.recordOutboxFailed(1);
                }
            } catch (Exception mex) {
                log.warn("Metric recording failed (outbox.failed relay): {}", mex.getMessage());
            }

            Duration backoff = calculateBackoff(event.getAttemptCount());
            log.warn("Failed to publish outbox event {} [{}] to topic {}: {}. Backing off for {}s",
                    event.getId(), event.getEventType(), event.getTopic(), ex.getMessage(), backoff.toSeconds());

            outboxService.markFailed(event.getId(), ex.getMessage(), backoff, maxRetries);
            return false;

        } finally {
            MDC.remove("correlationId");
        }
    }

    public Duration calculateBackoff(int attemptCount) {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at 60s
        long seconds = Math.min((long) Math.pow(2, Math.max(0, attemptCount - 1)), 60L);
        return Duration.ofSeconds(seconds);
    }

    public String getWorkerId() { return workerId; }
    public int getSuccessCount() { return successCount.get(); }
    public int getFailureCount() { return failureCount.get(); }
}
