package com.paymentledger.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Centralized, fail-safe Micrometer metrics service for the Distributed Payment & Ledger Platform.
 *
 * NON-NEGOTIABLE OBSERVABILITY INVARIANTS:
 * 1. Observability MUST NEVER become financial authority.
 * 2. Metrics failure must never cause business or payment failures (all methods swallow exceptions).
 * 3. Tag cardinality MUST remain strictly bounded (no UUIDs, emails, tokens, or raw amounts as labels).
 */
@Component
public class PlatformMetrics {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetrics.class);

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^.+@.+\\..+$");
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://.+");

    private final MeterRegistry meterRegistry;

    // Atomic gauges for bounded operational tracking
    private final AtomicLong outboxPendingGauge = new AtomicLong(0);
    private final AtomicLong outboxProcessingGauge = new AtomicLong(0);
    private final AtomicLong outboxOldestAgeSecondsGauge = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> kafkaLagGauges = new ConcurrentHashMap<>();

    @Autowired
    public PlatformMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge("outbox.pending", outboxPendingGauge);
            meterRegistry.gauge("outbox.processing", outboxProcessingGauge);
            meterRegistry.gauge("outbox.oldest.age", outboxOldestAgeSecondsGauge);
        }
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    // =========================================================================
    // Payment Metrics
    // =========================================================================

    public void recordPaymentCreated(String currency) {
        safeExecute(() -> {
            String safeCurrency = normalizeCurrency(currency);
            Counter.builder("payment.created")
                    .tag("currency", safeCurrency)
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordPaymentSettled(String currency, long durationMs) {
        safeExecute(() -> {
            String safeCurrency = normalizeCurrency(currency);
            Counter.builder("payment.settled")
                    .tag("currency", safeCurrency)
                    .register(meterRegistry)
                    .increment();

            Timer.builder("payment.capture.duration")
                    .tag("currency", safeCurrency)
                    .register(meterRegistry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        });
    }

    public void recordPaymentFailed(String reason) {
        safeExecute(() -> {
            String safeReason = sanitizeBoundedLabel("reason", reason);
            Counter.builder("payment.failed")
                    .tag("reason", safeReason)
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordPaymentPendingReconciliation(String reason) {
        safeExecute(() -> {
            String safeReason = sanitizeBoundedLabel("reason", reason);
            Counter.builder("payment.pending_reconciliation")
                    .tag("reason", safeReason)
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Ledger & Financial Consistency Metrics
    // =========================================================================

    public void recordLedgerTransactionPosted(String transactionType, String currency) {
        safeExecute(() -> {
            String safeType = sanitizeBoundedLabel("type", transactionType);
            String safeCurrency = normalizeCurrency(currency);
            Counter.builder("ledger.transaction.posted")
                    .tag("type", safeType)
                    .tag("currency", safeCurrency)
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordLedgerTransactionFailure(String transactionType, String reason) {
        safeExecute(() -> {
            String safeType = sanitizeBoundedLabel("type", transactionType);
            String safeReason = sanitizeBoundedLabel("reason", reason);
            Counter.builder("ledger.transaction.failure")
                    .tag("type", safeType)
                    .tag("reason", safeReason)
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordLedgerBalanceCheck(String accountType, boolean sufficient) {
        safeExecute(() -> {
            String safeAccountType = sanitizeBoundedLabel("accountType", accountType);
            Counter.builder("ledger.balance.check")
                    .tag("accountType", safeAccountType)
                    .tag("result", sufficient ? "SUFFICIENT" : "INSUFFICIENT")
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordLedgerInvariantFailure(String invariantType) {
        safeExecute(() -> {
            String safeType = sanitizeBoundedLabel("invariant", invariantType);
            Counter.builder("ledger.invariant.failure")
                    .tag("type", safeType)
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordLedgerUnbalancedTransaction() {
        safeExecute(() -> {
            Counter.builder("ledger.unbalanced.transaction")
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Refund Metrics
    // =========================================================================

    public void recordRefundCreated(String currency) {
        safeExecute(() -> {
            Counter.builder("refund.created")
                    .tag("currency", normalizeCurrency(currency))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordRefundSettled(String currency) {
        safeExecute(() -> {
            Counter.builder("refund.settled")
                    .tag("currency", normalizeCurrency(currency))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordRefundFailed(String reason) {
        safeExecute(() -> {
            Counter.builder("refund.failed")
                    .tag("reason", sanitizeBoundedLabel("reason", reason))
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Payout Metrics
    // =========================================================================

    public void recordPayoutCreated(String currency) {
        safeExecute(() -> {
            Counter.builder("payout.created")
                    .tag("currency", normalizeCurrency(currency))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordPayoutSettled(String currency) {
        safeExecute(() -> {
            Counter.builder("payout.settled")
                    .tag("currency", normalizeCurrency(currency))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordPayoutFailed(String reason) {
        safeExecute(() -> {
            Counter.builder("payout.failed")
                    .tag("reason", sanitizeBoundedLabel("reason", reason))
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Outbox Metrics
    // =========================================================================

    public void updateOutboxPendingCount(long count) {
        outboxPendingGauge.set(Math.max(0, count));
    }

    public void updateOutboxProcessingCount(long count) {
        outboxProcessingGauge.set(Math.max(0, count));
    }

    public void updateOutboxOldestAgeSeconds(long ageSeconds) {
        outboxOldestAgeSecondsGauge.set(Math.max(0, ageSeconds));
    }

    public void recordOutboxPublished(int count, long durationMs) {
        safeExecute(() -> {
            Counter.builder("outbox.published")
                    .register(meterRegistry)
                    .increment(count);

            Timer.builder("outbox.publish.duration")
                    .register(meterRegistry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        });
    }

    public void recordOutboxFailed(int count) {
        safeExecute(() -> {
            Counter.builder("outbox.failed")
                    .register(meterRegistry)
                    .increment(count);
        });
    }

    public void recordOutboxRetry(int count) {
        safeExecute(() -> {
            Counter.builder("outbox.retry")
                    .register(meterRegistry)
                    .increment(count);
        });
    }

    // =========================================================================
    // Kafka Metrics
    // =========================================================================

    public void recordKafkaConsumerRecord(String topic, String eventType) {
        safeExecute(() -> {
            Counter.builder("kafka.consumer.records")
                    .tag("topic", sanitizeBoundedLabel("topic", topic))
                    .tag("event_type", sanitizeBoundedLabel("eventType", eventType))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordKafkaConsumerError(String topic, String errorType) {
        safeExecute(() -> {
            Counter.builder("kafka.consumer.errors")
                    .tag("topic", sanitizeBoundedLabel("topic", topic))
                    .tag("error_type", sanitizeBoundedLabel("errorType", errorType))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void updateKafkaConsumerLag(String topic, long lag) {
        safeExecute(() -> {
            String safeTopic = sanitizeBoundedLabel("topic", topic);
            AtomicLong gauge = kafkaLagGauges.computeIfAbsent(safeTopic, t -> {
                AtomicLong al = new AtomicLong(0);
                if (meterRegistry != null) {
                    meterRegistry.gauge("kafka.consumer.lag", io.micrometer.core.instrument.Tags.of("topic", t), al);
                }
                return al;
            });
            gauge.set(Math.max(0, lag));
        });
    }

    public void recordKafkaDlt(String topic, String eventType) {
        safeExecute(() -> {
            Counter.builder("kafka.consumer.dlt")
                    .tag("topic", sanitizeBoundedLabel("topic", topic))
                    .tag("event_type", sanitizeBoundedLabel("eventType", eventType))
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Redis Metrics
    // =========================================================================

    public void recordRedisCacheHit(String cacheName) {
        safeExecute(() -> {
            Counter.builder("redis.cache.hit")
                    .tag("cache", sanitizeBoundedLabel("cache", cacheName))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordRedisCacheMiss(String cacheName) {
        safeExecute(() -> {
            Counter.builder("redis.cache.miss")
                    .tag("cache", sanitizeBoundedLabel("cache", cacheName))
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordRedisOperationFailure(String operation) {
        safeExecute(() -> {
            Counter.builder("redis.operation.failure")
                    .tag("operation", sanitizeBoundedLabel("operation", operation))
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Admin Metrics
    // =========================================================================

    public void recordAdminOperation(String action, String resourceType, boolean success) {
        safeExecute(() -> {
            Counter.builder("admin.operation.count")
                    .tag("action", sanitizeBoundedLabel("action", action))
                    .tag("resource", sanitizeBoundedLabel("resource", resourceType))
                    .tag("result", success ? "SUCCESS" : "FAILURE")
                    .register(meterRegistry)
                    .increment();
        });
    }

    public void recordAdminInvestigation(String resourceType) {
        safeExecute(() -> {
            Counter.builder("admin.investigation.request")
                    .tag("resource", sanitizeBoundedLabel("resource", resourceType))
                    .register(meterRegistry)
                    .increment();
        });
    }

    // =========================================================================
    // Cardinality Defense & Sanitization
    // =========================================================================

    public static boolean isUnboundedOrSensitive(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return UUID_PATTERN.matcher(trimmed).matches()
                || EMAIL_PATTERN.matcher(trimmed).matches()
                || URL_PATTERN.matcher(trimmed).matches()
                || trimmed.length() > 64;
    }

    public static String sanitizeBoundedLabel(String tagKey, String tagValue) {
        if (tagValue == null || tagValue.isBlank()) {
            return "UNKNOWN";
        }
        if (isUnboundedOrSensitive(tagValue)) {
            log.warn("Blocked high-cardinality/sensitive metric label attempt for tagKey='{}'", tagKey);
            return "REDACTED_HIGH_CARDINALITY";
        }
        // Normalize label to lowercase alphanumeric with underscores
        return tagValue.trim().toUpperCase().replaceAll("[^A-Z0-9_.-]", "_");
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "UNKNOWN";
        }
        String curr = currency.trim().toUpperCase();
        if (curr.length() > 5) {
            return "OTHER";
        }
        return curr;
    }

    private void safeExecute(Runnable action) {
        if (meterRegistry == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception ex) {
            // Non-negotiable invariant: Metrics failures must NEVER cause business failures.
            log.warn("Failed to record metric, continuing without impact to business state: {}", ex.getMessage());
        }
    }
}
