package com.paymentledger.messaging.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enforces durable, database-backed message deduplication for idempotent Kafka consumers.
 * <p>
 * Kafka delivers with at-least-once semantics. Deduplication records MUST participate
 * in the same database transaction as persistent consumer side-effects to guarantee
 * atomic rollback and prevent phantom deduplication if the side-effect fails.
 */
@Service
public class ConsumerDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(ConsumerDeduplicationService.class);

    private final JdbcTemplate jdbcTemplate;

    public ConsumerDeduplicationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Checks if a message has already been processed by the specified consumer group.
     */
    @Transactional(readOnly = true)
    public boolean isProcessed(String consumerGroup, UUID messageId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_messages WHERE consumer_group = ? AND message_id = ?",
                Long.class, consumerGroup, messageId);
        return count != null && count > 0;
    }

    /**
     * Records the consumed message within the caller's active database transaction (REQUIRED propagation).
     * If the consumer's subsequent persistent side-effects fail, this insert rolls back atomically with them,
     * ensuring Kafka retries will not be falsely blocked by a phantom deduplication marker.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordConsumed(String consumerGroup, UUID messageId, String eventType) {
        jdbcTemplate.update(
                "INSERT INTO consumed_messages (consumer_group, message_id, event_type, processed_at) VALUES (?, ?, ?, NOW())",
                consumerGroup, messageId, eventType);
    }

    /**
     * Standalone attempt to record consumed message. Participates in caller's transaction if present,
     * or begins a new transaction if called outside a transactional boundary.
     * Note: Uses REQUIRED propagation (not REQUIRES_NEW) so that it cannot commit prematurely before consumer side-effects.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean tryConsume(String consumerGroup, UUID messageId, String eventType) {
        if (isProcessed(consumerGroup, messageId)) {
            log.info("Duplicate event detected by consumer group {}: eventId={} [{}]. Skipping duplicate execution.",
                    consumerGroup, messageId, eventType);
            return false;
        }
        try {
            recordConsumed(consumerGroup, messageId, eventType);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate detected by consumer group {}: eventId={} [{}]. Skipping duplicate execution.",
                    consumerGroup, messageId, eventType);
            return false;
        }
    }
}
