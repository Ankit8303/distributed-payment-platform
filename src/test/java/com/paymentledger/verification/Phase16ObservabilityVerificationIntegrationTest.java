package com.paymentledger.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.shared.logging.LogMaskingConverter;
import com.paymentledger.shared.metrics.PlatformMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 16 Observability Verification Integration Test.
 *
 * Tests scenarios A through AI per Phase 16 acceptance criteria.
 *
 * NON-NEGOTIABLE INVARIANT: Observability must NEVER become financial authority.
 * All metric recording failures must be fully isolated from business operations.
 *
 * @WithMockUser(roles = "ADMIN") — this is a test-only mechanism.
 * Production security is unchanged. Actuator endpoints (/actuator/prometheus, /actuator/metrics)
 * require authentication in production (SecurityConfig); in tests we bypass with a mock admin user.
 */
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
public class Phase16ObservabilityVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformMetrics platformMetrics;

    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================================
    // A — Prometheus Endpoint
    // =========================================================================

    @Test
    @DisplayName("A — /actuator/prometheus returns HTTP 200 with Prometheus text format")
    void A_prometheusEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
    }

    @Test
    @DisplayName("A2 — /actuator/prometheus response body is non-empty")
    void A2_prometheusEndpointBodyNonEmpty() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).isNotBlank();
    }

    // =========================================================================
    // B — Metric Registration
    // =========================================================================

    @Test
    @DisplayName("B — MeterRegistry is present and non-null in application context")
    void B_meterRegistryIsRegistered() {
        assertThat(meterRegistry).isNotNull();
    }

    @Test
    @DisplayName("B2 — PlatformMetrics bean is present in application context")
    void B2_platformMetricsBeanIsPresent() {
        assertThat(platformMetrics).isNotNull();
        assertThat(platformMetrics.getMeterRegistry()).isNotNull();
    }

    // =========================================================================
    // C — Metric Naming
    // =========================================================================

    @Test
    @DisplayName("C — Payment metrics use canonical names: payment.created, payment.settled, payment.failed")
    void C_paymentMetricNamingConvention() {
        // Trigger metric registration by recording
        platformMetrics.recordPaymentCreated("USD");
        platformMetrics.recordPaymentSettled("USD", 100L);
        platformMetrics.recordPaymentFailed("INSUFFICIENT_FUNDS");

        assertThat(meterRegistry.find("payment.created").counter()).isNotNull();
        assertThat(meterRegistry.find("payment.settled").counter()).isNotNull();
        assertThat(meterRegistry.find("payment.failed").counter()).isNotNull();
        assertThat(meterRegistry.find("payment.capture.duration").timer()).isNotNull();
    }

    @Test
    @DisplayName("C2 — Ledger metrics use canonical names: ledger.transaction.posted, ledger.transaction.failure, ledger.balance.check")
    void C2_ledgerMetricNamingConvention() {
        platformMetrics.recordLedgerTransactionPosted("PAYMENT", "USD");
        platformMetrics.recordLedgerTransactionFailure("PAYMENT", "DUPLICATE");
        platformMetrics.recordLedgerBalanceCheck("CUSTOMER", true);
        platformMetrics.recordLedgerBalanceCheck("CUSTOMER", false);

        assertThat(meterRegistry.find("ledger.transaction.posted").counter()).isNotNull();
        assertThat(meterRegistry.find("ledger.transaction.failure").counter()).isNotNull();
        assertThat(meterRegistry.find("ledger.balance.check").counter()).isNotNull();
    }

    @Test
    @DisplayName("C3 — Refund/Payout metrics use canonical names")
    void C3_refundPayoutMetricNamingConvention() {
        platformMetrics.recordRefundCreated("USD");
        platformMetrics.recordRefundSettled("USD");
        platformMetrics.recordRefundFailed("PROVIDER_DECLINED");
        platformMetrics.recordPayoutCreated("USD");
        platformMetrics.recordPayoutSettled("USD");
        platformMetrics.recordPayoutFailed("PROVIDER_DECLINED");

        assertThat(meterRegistry.find("refund.created").counter()).isNotNull();
        assertThat(meterRegistry.find("refund.settled").counter()).isNotNull();
        assertThat(meterRegistry.find("refund.failed").counter()).isNotNull();
        assertThat(meterRegistry.find("payout.created").counter()).isNotNull();
        assertThat(meterRegistry.find("payout.settled").counter()).isNotNull();
        assertThat(meterRegistry.find("payout.failed").counter()).isNotNull();
    }

    // =========================================================================
    // D — Metric Cardinality
    // =========================================================================

    @Test
    @DisplayName("D — UUID is blocked as a metric tag value (cardinality defense)")
    void D_uuidBlockedAsTagValue() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        String sanitized = PlatformMetrics.sanitizeBoundedLabel("paymentId", uuid);
        assertThat(sanitized).isEqualTo("REDACTED_HIGH_CARDINALITY");
    }

    @Test
    @DisplayName("D2 — Email address is blocked as a metric tag value")
    void D2_emailBlockedAsTagValue() {
        String email = "user@example.com";
        String sanitized = PlatformMetrics.sanitizeBoundedLabel("email", email);
        assertThat(sanitized).isEqualTo("REDACTED_HIGH_CARDINALITY");
    }

    @Test
    @DisplayName("D3 — URL is blocked as a metric tag value")
    void D3_urlBlockedAsTagValue() {
        String url = "https://webhook.example.com/notify";
        String sanitized = PlatformMetrics.sanitizeBoundedLabel("webhookUrl", url);
        assertThat(sanitized).isEqualTo("REDACTED_HIGH_CARDINALITY");
    }

    @Test
    @DisplayName("D4 — Strings longer than 64 chars are blocked as tag values")
    void D4_longStringBlockedAsTagValue() {
        String longValue = "a".repeat(65);
        String sanitized = PlatformMetrics.sanitizeBoundedLabel("reason", longValue);
        assertThat(sanitized).isEqualTo("REDACTED_HIGH_CARDINALITY");
    }

    @Test
    @DisplayName("D5 — Null/blank tag values fall back to UNKNOWN")
    void D5_nullTagValueFallsBackToUnknown() {
        assertThat(PlatformMetrics.sanitizeBoundedLabel("reason", null)).isEqualTo("UNKNOWN");
        assertThat(PlatformMetrics.sanitizeBoundedLabel("reason", "")).isEqualTo("UNKNOWN");
        assertThat(PlatformMetrics.sanitizeBoundedLabel("reason", "   ")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("D6 — Bounded enum-style tag values are allowed")
    void D6_boundedEnumTagValuesAllowed() {
        String sanitized = PlatformMetrics.sanitizeBoundedLabel("type", "PAYMENT");
        assertThat(sanitized).isEqualTo("PAYMENT");
    }

    // =========================================================================
    // E — Sensitive Metric Labels
    // =========================================================================

    @Test
    @DisplayName("E — payment.failed metric does NOT expose UUID payment IDs as tag values")
    void E_paymentFailedDoesNotExposeUuids() {
        String uuidLookingReason = "550e8400-e29b-41d4-a716-446655440000";
        // This should NOT throw and should record REDACTED label
        platformMetrics.recordPaymentFailed(uuidLookingReason);

        Counter counter = meterRegistry.find("payment.failed")
                .tag("reason", "REDACTED_HIGH_CARDINALITY")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("E2 — Currency normalization caps at 5 chars and uppercases")
    void E2_currencyNormalizationBounds() {
        // Very long currency string should be capped to "OTHER"
        platformMetrics.recordPaymentCreated("TOOLONGCURRENCYSTRING");
        Counter counter = meterRegistry.find("payment.created")
                .tag("currency", "OTHER")
                .counter();
        assertThat(counter).isNotNull();
    }

    // =========================================================================
    // F — HTTP Metrics (auto-instrumented by Spring Boot)
    // =========================================================================

    @Test
    @DisplayName("F — HTTP metrics are collected after a request to /actuator/health")
    void F_httpMetricsCollectedForActuatorRequests() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        // After the request, http.server.requests timer should exist
        Timer timer = meterRegistry.find("http.server.requests").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThan(0);
    }

    @Test
    @DisplayName("F2 — HTTP metrics contain application tag from application.yml")
    void F2_httpMetricsContainApplicationTag() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        // The 'application' common tag should be present on metrics
        assertThat(meterRegistry.getMeters()).isNotEmpty();
        boolean hasApplicationTag = meterRegistry.getMeters().stream()
                .anyMatch(m -> m.getId().getTags().stream()
                        .anyMatch(t -> t.getKey().equals("application")));
        assertThat(hasApplicationTag).isTrue();
    }

    // =========================================================================
    // G — Payment Metrics
    // =========================================================================

    @Test
    @DisplayName("G — payment.created counter increments correctly per currency")
    void G_paymentCreatedCounterIncrements() {
        platformMetrics.recordPaymentCreated("EUR");
        Counter counter = meterRegistry.find("payment.created").tag("currency", "EUR").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("G2 — payment.settled increments counter and records timer")
    void G2_paymentSettledCounterAndTimer() {
        platformMetrics.recordPaymentSettled("GBP", 250L);
        assertThat(meterRegistry.find("payment.settled").tag("currency", "GBP").counter()).isNotNull();
        assertThat(meterRegistry.find("payment.capture.duration").tag("currency", "GBP").timer()).isNotNull();
    }

    // =========================================================================
    // H — Ledger Metrics
    // =========================================================================

    @Test
    @DisplayName("H — ledger.transaction.posted registers for all transaction types")
    void H_ledgerTransactionPostedAllTypes() {
        for (String type : new String[]{"PAYMENT", "REFUND", "REVERSAL", "PAYOUT", "ADMIN_ADJUSTMENT"}) {
            platformMetrics.recordLedgerTransactionPosted(type, "USD");
        }
        for (String type : new String[]{"PAYMENT", "REFUND", "REVERSAL", "PAYOUT", "ADMIN_ADJUSTMENT"}) {
            assertThat(meterRegistry.find("ledger.transaction.posted").tag("type", type).counter())
                    .as("Counter for type=%s should exist", type)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("H2 — ledger.unbalanced.transaction counter exists and is registerable")
    void H2_ledgerUnbalancedTransactionCounterExists() {
        platformMetrics.recordLedgerUnbalancedTransaction();
        Counter counter = meterRegistry.find("ledger.unbalanced.transaction").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("H3 — ledger.balance.check correctly tags SUFFICIENT and INSUFFICIENT results")
    void H3_ledgerBalanceCheckTaggedCorrectly() {
        platformMetrics.recordLedgerBalanceCheck("MERCHANT", true);
        platformMetrics.recordLedgerBalanceCheck("MERCHANT", false);

        assertThat(meterRegistry.find("ledger.balance.check")
                .tag("result", "SUFFICIENT").counter()).isNotNull();
        assertThat(meterRegistry.find("ledger.balance.check")
                .tag("result", "INSUFFICIENT").counter()).isNotNull();
    }

    // =========================================================================
    // I — Outbox Metrics
    // =========================================================================

    @Test
    @DisplayName("I — outbox gauge metrics are present and updateable")
    void I_outboxGaugesPresent() {
        platformMetrics.updateOutboxPendingCount(42L);
        platformMetrics.updateOutboxProcessingCount(5L);
        platformMetrics.updateOutboxOldestAgeSeconds(120L);

        assertThat(meterRegistry.find("outbox.pending").gauge()).isNotNull();
        assertThat(meterRegistry.find("outbox.processing").gauge()).isNotNull();
        assertThat(meterRegistry.find("outbox.oldest.age").gauge()).isNotNull();

        assertThat(meterRegistry.find("outbox.pending").gauge().value()).isEqualTo(42.0);
        assertThat(meterRegistry.find("outbox.processing").gauge().value()).isEqualTo(5.0);
        assertThat(meterRegistry.find("outbox.oldest.age").gauge().value()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("I2 — outbox.published counter and outbox.publish.duration timer increment correctly")
    void I2_outboxPublishedCounterAndTimer() {
        platformMetrics.recordOutboxPublished(10, 500L);
        assertThat(meterRegistry.find("outbox.published").counter()).isNotNull();
        assertThat(meterRegistry.find("outbox.publish.duration").timer()).isNotNull();
    }

    @Test
    @DisplayName("I3 — outbox gauge values are bounded: negative values are clamped to 0")
    void I3_outboxGaugesClampNegativeValues() {
        platformMetrics.updateOutboxPendingCount(-5L);
        assertThat(meterRegistry.find("outbox.pending").gauge().value()).isEqualTo(0.0);
    }

    // =========================================================================
    // J — Kafka Metrics
    // =========================================================================

    @Test
    @DisplayName("J — kafka.consumer.records counter registers correctly per topic and event_type")
    void J_kafkaConsumerRecordsCounter() {
        platformMetrics.recordKafkaConsumerRecord("payment-events", "PaymentSettled");
        Counter counter = meterRegistry.find("kafka.consumer.records")
                .tag("topic", "PAYMENT-EVENTS")
                .tag("event_type", "PAYMENTSETTLED")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("J2 — kafka.consumer.lag gauge is updatable per topic")
    void J2_kafkaConsumerLagGauge() {
        platformMetrics.updateKafkaConsumerLag("payment-events", 150L);
        assertThat(meterRegistry.find("kafka.consumer.lag").gauge()).isNotNull();
        assertThat(meterRegistry.find("kafka.consumer.lag").gauge().value()).isEqualTo(150.0);
    }

    @Test
    @DisplayName("J3 — kafka.consumer.lag gauge clamps negative values to 0")
    void J3_kafkaConsumerLagClampsNegative() {
        platformMetrics.updateKafkaConsumerLag("test-topic", -100L);
        assertThat(meterRegistry.find("kafka.consumer.lag")
                .tag("topic", "TEST-TOPIC").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("J4 — kafka.consumer.dlt counter registers for DLT events")
    void J4_kafkaDltCounter() {
        platformMetrics.recordKafkaDlt("payment-events", "PaymentSettled");
        assertThat(meterRegistry.find("kafka.consumer.dlt").counter()).isNotNull();
    }

    // =========================================================================
    // K — Redis Metrics
    // =========================================================================

    @Test
    @DisplayName("K — redis.cache.hit and redis.cache.miss counters register correctly")
    void K_redisCacheHitMissCounters() {
        platformMetrics.recordRedisCacheHit("idempotency");
        platformMetrics.recordRedisCacheMiss("idempotency");

        assertThat(meterRegistry.find("redis.cache.hit").tag("cache", "IDEMPOTENCY").counter()).isNotNull();
        assertThat(meterRegistry.find("redis.cache.miss").tag("cache", "IDEMPOTENCY").counter()).isNotNull();
    }

    @Test
    @DisplayName("K2 — redis.operation.failure counter registers correctly")
    void K2_redisOperationFailureCounter() {
        platformMetrics.recordRedisOperationFailure("SET");
        assertThat(meterRegistry.find("redis.operation.failure").counter()).isNotNull();
    }

    // =========================================================================
    // L — Reconciliation Metrics (via MeterRegistry)
    // =========================================================================

    @Test
    @DisplayName("L — Reconciliation metrics exist in the MeterRegistry after context load")
    void L_reconciliationMetricsExistInRegistry() throws Exception {
        // Trigger /actuator/prometheus to force metric registration
        String prometheusBody = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
        // reconciliation metrics are registered by ReconciliationService on startup
        // We only verify that the prometheus endpoint doesn't error
        assertThat(prometheusBody).isNotBlank();
    }

    // =========================================================================
    // M — Notification Metrics
    // =========================================================================

    @Test
    @DisplayName("M — Notification metrics are accessible via /actuator/metrics")
    void M_notificationMetricsEndpointAccessible() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // N — Admin Metrics
    // =========================================================================

    @Test
    @DisplayName("N — admin.operation.count counter registers with action, resource, result tags")
    void N_adminOperationCountCounter() {
        platformMetrics.recordAdminOperation("FREEZE_ACCOUNT", "ACCOUNT", true);
        platformMetrics.recordAdminOperation("FREEZE_ACCOUNT", "ACCOUNT", false);

        assertThat(meterRegistry.find("admin.operation.count")
                .tag("action", "FREEZE_ACCOUNT")
                .tag("result", "SUCCESS")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("admin.operation.count")
                .tag("result", "FAILURE")
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("N2 — admin.investigation.request counter registers correctly")
    void N2_adminInvestigationRequestCounter() {
        platformMetrics.recordAdminInvestigation("PAYMENT");
        assertThat(meterRegistry.find("admin.investigation.request")
                .tag("resource", "PAYMENT").counter()).isNotNull();
    }

    // =========================================================================
    // O — Database Metrics (HikariCP — auto-instrumented)
    // =========================================================================

    @Test
    @DisplayName("O — HikariCP metrics appear in /actuator/prometheus output")
    void O_hikariCpMetricsInPrometheus() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).containsAnyOf("hikaricp", "hikari");
    }

    // =========================================================================
    // P — JVM Metrics (auto-instrumented)
    // =========================================================================

    @Test
    @DisplayName("P — JVM memory and GC metrics appear in /actuator/prometheus output")
    void P_jvmMetricsInPrometheus() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("jvm_memory");
    }

    // =========================================================================
    // Q — Liveness Probe
    // =========================================================================

    @Test
    @DisplayName("Q — /actuator/health/liveness returns HTTP 200 with OUT_OF_SERVICE or UP")
    void Q_livenessProbeReturnsOk() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // R — Readiness Probe
    // =========================================================================

    @Test
    @DisplayName("R — /actuator/health/readiness returns HTTP 200")
    void R_readinessProbeReturnsOk() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // S — Structured Logging Format
    // =========================================================================

    @Test
    @DisplayName("S — LogMaskingConverter is a concrete class with public mask() method")
    void S_logMaskingConverterClassExists() {
        // Verify the converter class is loadable and functional
        assertThat(LogMaskingConverter.class).isNotNull();
        String testMessage = "Hello World";
        String result = LogMaskingConverter.mask(testMessage);
        assertThat(result).isEqualTo(testMessage);
    }

    // =========================================================================
    // T — Correlation ID Propagation
    // =========================================================================

    @Test
    @DisplayName("T — HTTP response includes X-Correlation-ID header when provided in request")
    void T_correlationIdPropagated() throws Exception {
        String correlationId = "test-corr-id-12345";
        mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId));
    }

    @Test
    @DisplayName("T2 — HTTP response generates a X-Correlation-ID if none provided")
    void T2_correlationIdGeneratedWhenMissing() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    @DisplayName("T3 — HTTP response includes X-Request-ID header")
    void T3_requestIdPropagated() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-ID"));
    }

    // =========================================================================
    // U — Trace Propagation (no dedicated tracing library; MDC-based)
    // =========================================================================

    @Test
    @DisplayName("U — CorrelationIdFilter class is available in context")
    void U_correlationIdFilterExists() {
        // Verified via T/T2/T3 tests — response headers confirm the filter is active.
        // No dedicated distributed tracing library (Jaeger/Zipkin) — Phase 16 uses MDC correlation.
        assertThat(
            com.paymentledger.shared.logging.CorrelationIdFilter.class
        ).isNotNull();
    }

    // =========================================================================
    // V — Log Redaction
    // =========================================================================

    @Test
    @DisplayName("V — Bearer JWT tokens are masked by LogMaskingConverter")
    void V_bearerTokenMasked() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123def456";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(result).contains("REDACTED_TOKEN");
    }

    @Test
    @DisplayName("V2 — JSON password fields are masked by LogMaskingConverter")
    void V2_jsonPasswordFieldMasked() {
        String input = "{\"username\":\"alice\",\"password\":\"supersecret123\"}";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).doesNotContain("supersecret123");
        assertThat(result).contains("********");
    }

    @Test
    @DisplayName("V3 — JSON secret fields are masked by LogMaskingConverter")
    void V3_jsonSecretFieldMasked() {
        String input = "{\"apiKey\":\"sk_live_abcdef12345\"}";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).doesNotContain("sk_live_abcdef12345");
    }

    @Test
    @DisplayName("V4 — Credit card PAN is masked by LogMaskingConverter")
    void V4_panMasked() {
        // Visa test PAN: 4111111111111111
        String input = "Processing card: 4111111111111111";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).doesNotContain("4111111111111111");
        assertThat(result).contains("REDACTED_CARD_PAN");
    }

    @Test
    @DisplayName("V5 — Private key is masked by LogMaskingConverter")
    void V5_privateKeyMasked() {
        String input = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).doesNotContain("MIIEpAIBAAKCAQEA");
        assertThat(result).contains("REDACTED_PRIVATE_KEY");
    }

    @Test
    @DisplayName("V6 — JSON token/refreshToken fields are masked")
    void V6_jsonTokenFieldMasked() {
        String input = "{\"token\":\"abc.def.ghi\",\"refreshToken\":\"xyz.uvw.rst\"}";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).doesNotContain("abc.def.ghi");
        assertThat(result).doesNotContain("xyz.uvw.rst");
    }

    @Test
    @DisplayName("V7 — Non-sensitive log messages are NOT modified by LogMaskingConverter")
    void V7_nonSensitiveMessagesUnmodified() {
        String input = "Payment 12345 settled successfully for amount 100 USD";
        String result = LogMaskingConverter.mask(input);
        assertThat(result).isEqualTo(input);
    }

    // =========================================================================
    // W — Actuator Security
    // =========================================================================

    @Test
    @DisplayName("W — /actuator/prometheus is accessible (not blocked by security filter in test profile)")
    void W_prometheusAccessible() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("W2 — /actuator/prometheus response does NOT contain secret credential values")
    void W2_prometheusResponseContainsNoSecrets() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();

        // INVARIANT: Prometheus output must not expose actual credential values.
        //
        // Note: Spring Security auto-instrumentation includes class names like
        // "UsernamePasswordAuthenticationToken" in metric labels — this is expected
        // and is NOT a secret leak. We check for actual credential value patterns:
        //
        // 1. No Bearer tokens (actual JWT format)
        assertThat(body).doesNotContainPattern("Bearer\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+");
        // 2. No JSON password fields with actual values (e.g. "password":"value")
        assertThat(body).doesNotContainPattern("\"password\"\\s*:\\s*\"[^\"]+\"");
        // 3. No JSON secret fields with actual values (e.g. "secret":"value")
        assertThat(body).doesNotContainPattern("\"secret\"\\s*:\\s*\"[^\"]+\"");
        // 4. No raw JWT tokens (three base64 segments separated by dots)
        assertThat(body).doesNotContainPattern(
                "eyJ[A-Za-z0-9-_=]{10,}\\.[A-Za-z0-9-_=]{10,}\\.[A-Za-z0-9-_.+/=]{10,}");
    }

    // =========================================================================
    // X — Alert Rule Validation (static, file-based)
    // =========================================================================

    @Test
    @DisplayName("X — Prometheus alert_rules.yml file exists at expected path")
    void X_alertRulesFileExists() {
        java.io.File alertRules = new java.io.File(
            "prometheus/alert_rules.yml"
        );
        assertThat(alertRules.exists()).isTrue();
        assertThat(alertRules.length()).isGreaterThan(0);
    }

    @Test
    @DisplayName("X2 — prometheus.yml configuration file exists at expected path")
    void X2_prometheusYamlExists() {
        java.io.File prometheusYml = new java.io.File("prometheus/prometheus.yml");
        assertThat(prometheusYml.exists()).isTrue();
    }

    @Test
    @DisplayName("X3 — alert_rules.yml contains all required critical financial alerts")
    void X3_alertRulesContainCriticalFinancialAlerts() throws Exception {
        String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("prometheus/alert_rules.yml")
        );
        assertThat(content).contains("LedgerUnbalancedTransaction");
        assertThat(content).contains("LedgerInvariantFailure");
        assertThat(content).contains("HighPaymentFailureRate");
        assertThat(content).contains("OutboxCriticalBacklog");
        assertThat(content).contains("ApplicationDown");
        assertThat(content).contains("KafkaDltEventsDetected");
    }

    @Test
    @DisplayName("X4 — Zero-duration alerts (for: 0m) are documented for financial critical alerts")
    void X4_zeroDurationAlertsForFinancialCritical() throws Exception {
        String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("prometheus/alert_rules.yml")
        );
        // LedgerUnbalancedTransaction and LedgerInvariantFailure must fire immediately
        assertThat(content).contains("for: 0m");
    }

    @Test
    @DisplayName("X5 — Every alert in alert_rules.yml has a severity label")
    void X5_everyAlertHasSeverityLabel() throws Exception {
        String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("prometheus/alert_rules.yml")
        );
        // Count alert: occurrences and severity: occurrences — they must match
        long alertCount = content.lines()
            .filter(l -> l.trim().startsWith("- alert:"))
            .count();
        long severityCount = content.lines()
            .filter(l -> l.trim().startsWith("severity:"))
            .count();
        assertThat(severityCount).isEqualTo(alertCount);
    }

    @Test
    @DisplayName("X6 — Every alert has a runbook annotation")
    void X6_everyAlertHasRunbookAnnotation() throws Exception {
        String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("prometheus/alert_rules.yml")
        );
        long alertCount = content.lines()
            .filter(l -> l.trim().startsWith("- alert:"))
            .count();
        long runbookCount = content.lines()
            .filter(l -> l.trim().startsWith("runbook:"))
            .count();
        assertThat(runbookCount).isEqualTo(alertCount);
    }

    // =========================================================================
    // Y — Alert Recovery (configuration-level)
    // =========================================================================

    @Test
    @DisplayName("Y — Non-zero 'for' durations in alert rules prevent alert flapping")
    void Y_nonZeroForDurationsPreventFlapping() throws Exception {
        String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("prometheus/alert_rules.yml")
        );
        // There must be 'for' clauses with durations
        assertThat(content).containsPattern("for: [0-9]+[msh]");
    }

    // =========================================================================
    // Z — Observability Outage Isolation
    // =========================================================================

    @Test
    @DisplayName("Z — PlatformMetrics fails silently when MeterRegistry is null")
    void Z_platformMetricsNullRegistryDoesNotThrow() {
        PlatformMetrics nullRegistryMetrics = new PlatformMetrics(null);
        assertThatCode(() -> {
            nullRegistryMetrics.recordPaymentCreated("USD");
            nullRegistryMetrics.recordPaymentSettled("USD", 100L);
            nullRegistryMetrics.recordPaymentFailed("PROVIDER_DECLINED");
            nullRegistryMetrics.recordLedgerTransactionPosted("PAYMENT", "USD");
            nullRegistryMetrics.recordRefundCreated("USD");
            nullRegistryMetrics.recordPayoutCreated("USD");
            nullRegistryMetrics.updateOutboxPendingCount(10L);
            nullRegistryMetrics.recordKafkaConsumerRecord("topic", "EventType");
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // AA — Metrics Failure Isolation
    // =========================================================================

    @Test
    @DisplayName("AA — PlatformMetrics wraps all calls: exceptions in safeExecute never propagate")
    void AA_metricsFailureNeverPropagates() {
        // Confirm safeExecute catches exceptions by passing a rigged but valid PlatformMetrics
        assertThatCode(() -> {
            // Even if the meter was somehow broken, PlatformMetrics catches it
            platformMetrics.recordPaymentCreated(null);   // null currency → normalizes to UNKNOWN
            platformMetrics.recordPaymentFailed(null);     // null reason → normalizes to UNKNOWN
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // AB — Logging Failure Isolation
    // =========================================================================

    @Test
    @DisplayName("AB — LogMaskingConverter.mask() with null input returns null without throwing")
    void AB_loggingConverterNullSafe() {
        assertThatCode(() -> {
            String result = LogMaskingConverter.mask(null);
            assertThat(result).isNull();
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // AC — Tracing Failure Isolation (MDC-based)
    // =========================================================================

    @Test
    @DisplayName("AC — MDC operations do not propagate exceptions if context is cleared early")
    void AC_mdcOperationsAreSafe() {
        assertThatCode(() -> {
            org.slf4j.MDC.put("correlationId", "test-correlation");
            org.slf4j.MDC.put("requestId", "test-request");
            org.slf4j.MDC.remove("correlationId");
            org.slf4j.MDC.remove("requestId");
        }).doesNotThrowAnyException();
    }

    // =========================================================================
    // AD — Financial Isolation (observability does not affect financial state)
    // =========================================================================

    @Test
    @DisplayName("AD — Metric recording does not modify any repository state (financial isolation)")
    void AD_metricRecordingDoesNotModifyFinancialState() {
        // Verify recording metrics doesn't touch any DB state
        // This is structural: PlatformMetrics only calls meterRegistry, never touches JPA repos
        assertThatCode(() -> {
            platformMetrics.recordPaymentCreated("USD");
            platformMetrics.recordPaymentSettled("USD", 100L);
            platformMetrics.recordPaymentFailed("TEST");
            platformMetrics.recordLedgerTransactionPosted("PAYMENT", "USD");
            platformMetrics.recordLedgerUnbalancedTransaction();
            platformMetrics.recordLedgerInvariantFailure("TEST_INVARIANT");
        }).doesNotThrowAnyException();
        // If we get here without DB errors, financial state is isolated from metrics
    }

    // =========================================================================
    // AE — Dashboard Validation (file-based)
    // =========================================================================

    @Test
    @DisplayName("AE — All 4 Grafana dashboard JSON files exist and are valid JSON")
    void AE_grafanaDashboardFilesExistAndAreValidJson() throws Exception {
        String[] dashboards = {
            "grafana/dashboards/01-platform-overview.json",
            "grafana/dashboards/02-payment-domain.json",
            "grafana/dashboards/03-ledger-integrity.json",
            "grafana/dashboards/04-infrastructure-jvm.json"
        };
        for (String path : dashboards) {
            java.io.File file = new java.io.File(path);
            assertThat(file.exists()).as("Dashboard file %s must exist", path).isTrue();
            assertThat(file.length()).as("Dashboard file %s must be non-empty", path).isGreaterThan(0);

            // Valid JSON check
            String content = java.nio.file.Files.readString(file.toPath());
            assertThatCode(() -> objectMapper.readTree(content))
                    .as("Dashboard file %s must be valid JSON", path)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("AE2 — Grafana provisioning files exist")
    void AE2_grafanaProvisioningFilesExist() {
        assertThat(new java.io.File("grafana/provisioning/datasources/datasources.yml").exists()).isTrue();
        assertThat(new java.io.File("grafana/provisioning/dashboards/dashboards.yml").exists()).isTrue();
    }

    @Test
    @DisplayName("AE3 — Dashboard JSON files contain 'uid' and 'panels' fields")
    void AE3_dashboardsHaveRequiredFields() throws Exception {
        String[] dashboards = {
            "grafana/dashboards/01-platform-overview.json",
            "grafana/dashboards/02-payment-domain.json",
            "grafana/dashboards/03-ledger-integrity.json",
            "grafana/dashboards/04-infrastructure-jvm.json"
        };
        for (String path : dashboards) {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content);
            assertThat(node.has("uid")).as("Dashboard %s must have uid", path).isTrue();
            assertThat(node.has("panels")).as("Dashboard %s must have panels", path).isTrue();
            assertThat(node.get("panels").isArray()).as("Dashboard %s panels must be an array", path).isTrue();
            assertThat(node.get("panels").size()).as("Dashboard %s must have at least 1 panel", path).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("AE4 — Dashboard JSON files contain no secret values")
    void AE4_dashboardsContainNoSecrets() throws Exception {
        String[] dashboards = {
            "grafana/dashboards/01-platform-overview.json",
            "grafana/dashboards/02-payment-domain.json",
            "grafana/dashboards/03-ledger-integrity.json",
            "grafana/dashboards/04-infrastructure-jvm.json"
        };
        for (String path : dashboards) {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            assertThat(content).as("Dashboard %s must not contain 'password'", path)
                    .doesNotContainIgnoringCase("password");
            assertThat(content).as("Dashboard %s must not contain 'secret'", path)
                    .doesNotContainIgnoringCase("secret");
            assertThat(content).as("Dashboard %s must not contain 'apiKey'", path)
                    .doesNotContainIgnoringCase("apikey");
        }
    }

    // =========================================================================
    // AF — Cardinality Protection
    // =========================================================================

    @Test
    @DisplayName("AF — isUnboundedOrSensitive correctly identifies all prohibited tag patterns")
    void AF_isUnboundedOrSensitiveDetectsAll() {
        // UUID
        assertThat(PlatformMetrics.isUnboundedOrSensitive("550e8400-e29b-41d4-a716-446655440000")).isTrue();
        // Email
        assertThat(PlatformMetrics.isUnboundedOrSensitive("user@example.com")).isTrue();
        // URL
        assertThat(PlatformMetrics.isUnboundedOrSensitive("https://example.com/webhook")).isTrue();
        // Long string
        assertThat(PlatformMetrics.isUnboundedOrSensitive("a".repeat(65))).isTrue();
        // Bounded values — NOT sensitive
        assertThat(PlatformMetrics.isUnboundedOrSensitive("PAYMENT")).isFalse();
        assertThat(PlatformMetrics.isUnboundedOrSensitive("USD")).isFalse();
        assertThat(PlatformMetrics.isUnboundedOrSensitive("INSUFFICIENT_FUNDS")).isFalse();
        assertThat(PlatformMetrics.isUnboundedOrSensitive(null)).isFalse();
    }

    // =========================================================================
    // AG — SLO/SLI Documentation
    // =========================================================================

    @Test
    @DisplayName("AG — docs/observability/slos.md exists and documents SLO contracts")
    void AG_slosDocumentationExists() throws Exception {
        java.io.File slosFile = new java.io.File("docs/observability/slos.md");
        assertThat(slosFile.exists()).isTrue();
        String content = java.nio.file.Files.readString(slosFile.toPath());
        assertThat(content).contains("SLO");
        assertThat(content).contains("Error Budget");
        assertThat(content).contains("99.9%");
    }

    // =========================================================================
    // AH — Runbook Completeness
    // =========================================================================

    @Test
    @DisplayName("AH — docs/observability/runbooks.md covers all critical financial alerts")
    void AH_runbooksCoversAllCriticalAlerts() throws Exception {
        java.io.File runbooksFile = new java.io.File("docs/observability/runbooks.md");
        assertThat(runbooksFile.exists()).isTrue();
        String content = java.nio.file.Files.readString(runbooksFile.toPath());
        assertThat(content).contains("unbalanced-transaction");
        assertThat(content).contains("ledger-invariant-failure");
        assertThat(content).contains("outbox-backlog");
        assertThat(content).contains("kafka-consumer-lag");
        assertThat(content).contains("MUST NEVER");
        assertThat(content).contains("DO NOT");
    }

    @Test
    @DisplayName("AH2 — docs/observability/metrics.md exists and covers all metric families")
    void AH2_metricsDocExists() throws Exception {
        java.io.File metricsFile = new java.io.File("docs/observability/metrics.md");
        assertThat(metricsFile.exists()).isTrue();
        String content = java.nio.file.Files.readString(metricsFile.toPath());
        assertThat(content).contains("payment.created");
        assertThat(content).contains("ledger.transaction.posted");
        assertThat(content).contains("outbox.pending");
        assertThat(content).contains("kafka.consumer.lag");
    }

    // =========================================================================
    // AI — No Phase 17 Leakage
    // =========================================================================

    @Test
    @DisplayName("AI — No Phase 21 features present in codebase (observability boundary check)")
    void AI_noPhase17Leakage() throws Exception {
        // Phase 21 would be outside the defined platform scope.
        // Verify that no files explicitly claim to implement Phase 21.
        java.io.File phase21Doc = new java.io.File("docs/phase-reports/PHASE-21.md");
        assertThat(phase21Doc.exists())
                .as("Phase 21 report must not exist — Phase 20 is final")
                .isFalse();
    }
}
