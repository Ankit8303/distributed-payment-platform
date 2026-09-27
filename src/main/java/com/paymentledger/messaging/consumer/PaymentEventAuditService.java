package com.paymentledger.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentledger.messaging.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transactional processor for payment event auditing.
 * <p>
 * Executes deduplication recording and persistent audit log persistence within ONE atomic
 * PostgreSQL transaction:
 * <pre>
 * Kafka message
 * ↓
 * BEGIN DATABASE TRANSACTION
 * ↓
 * insert consumed_messages
 * ↓
 * perform persistent consumer side-effect (insert payment_event_audits)
 * ↓
 * COMMIT
 * ↓
 * Kafka offset acknowledgement
 * </pre>
 * If the side-effect fails, both the audit record and the consumed_messages marker roll back,
 * ensuring Kafka retry can safely complete the persistent effect without phantom deduplication.
 */
@Service
public class PaymentEventAuditService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventAuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConsumerDeduplicationService deduplicationService;

    // Test hook for simulating transient failures between deduplication marker and side-effect commit
    private final AtomicBoolean simulateTransientFailure = new AtomicBoolean(false);

    public PaymentEventAuditService(JdbcTemplate jdbcTemplate, ConsumerDeduplicationService deduplicationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.deduplicationService = deduplicationService;
    }

    /**
     * Atomically executes deduplication check, marker insert, and audit side-effect.
     *
     * @return true if the event was processed; false if it was already processed (duplicate skipped)
     */
    @Transactional
    public boolean processAndAudit(String consumerGroup, EventEnvelope<JsonNode> envelope, String rawPayload) {
        UUID eventId = envelope.eventId();
        String eventType = envelope.eventType();

        // 1. Idempotency Check: verify if already committed
        if (deduplicationService.isProcessed(consumerGroup, eventId)) {
            log.info("Consumer {} already processed eventId={}. Skipping duplicate.", consumerGroup, eventId);
            return false;
        }

        // 2. Insert deduplication marker in the SAME transaction
        deduplicationService.recordConsumed(consumerGroup, eventId, eventType);

        // 3. Transient failure simulation hook for testing transaction atomicity & retry safety
        if (simulateTransientFailure.compareAndSet(true, false)) {
            log.warn("Simulating transient consumer processing failure for eventId={} before side-effect commits! Rolling back TX.", eventId);
            throw new RuntimeException("Simulated transient consumer failure during side-effect execution");
        }

        // 4. Perform persistent consumer side-effect in the SAME transaction
        jdbcTemplate.update(
                "INSERT INTO payment_event_audits (event_id, event_type, aggregate_id, payload_json, created_at) VALUES (?, ?, ?, ?, NOW())",
                eventId, eventType, envelope.aggregateId(), rawPayload
        );

        log.info("Consumer {} successfully recorded deduplication and persisted audit record for eventId={}",
                consumerGroup, eventId);
        return true;
    }

    public void setSimulateTransientFailure(boolean simulate) {
        this.simulateTransientFailure.set(simulate);
    }

    public int getAuditRecordCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_audits WHERE event_id = ?",
                Integer.class, eventId);
        return count != null ? count : 0;
    }
}
