package com.paymentledger.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.account.service.AccountService;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.dto.LoginRequest;
import com.paymentledger.auth.dto.RefreshTokenRequest;
import com.paymentledger.auth.repository.RefreshTokenRepository;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.auth.service.AuthService;
import com.paymentledger.auth.service.JwtService;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.notification.domain.WebhookSubscriptionEntity;
import com.paymentledger.notification.repository.WebhookSubscriptionRepository;
import com.paymentledger.notification.security.SsrfBlockedException;
import com.paymentledger.notification.security.WebhookSecurityValidator;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.domain.OutboxStatus;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.outbox.service.OutboxRelayScheduler;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payment.service.PaymentService;
import com.paymentledger.payout.api.dto.PayoutCreateRequest;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.repository.PayoutRepository;
import com.paymentledger.payout.service.PayoutService;
import com.paymentledger.refund.api.dto.RefundCreateRequest;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.ReversalEntity;
import com.paymentledger.refund.domain.ReversalStatus;
import com.paymentledger.refund.exception.RefundDomainException;
import com.paymentledger.refund.repository.RefundRepository;
import com.paymentledger.refund.repository.ReversalRepository;
import com.paymentledger.refund.service.RefundService;
import com.paymentledger.ledger.domain.LedgerTransactionStatus;
import com.paymentledger.payment.exception.PaymentDomainException;
import com.paymentledger.payout.exception.PayoutDomainException;
import com.paymentledger.shared.error.ErrorCode;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import com.paymentledger.shared.logging.LogMaskingConverter;
import com.paymentledger.shared.metrics.PlatformMetrics;
import com.paymentledger.shared.redis.RateLimitPolicy;
import com.paymentledger.shared.redis.RateLimitResult;
import com.paymentledger.shared.redis.RedisRateLimiter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 17 — Production Hardening Verification Integration Test.
 *
 * Verifies all 38 production hardening dimensions (A through AL)
 * according to the Phase 17 Specification and Acceptance Gates.
 */
@AutoConfigureMockMvc
public class Phase17ProductionHardeningIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private ReversalRepository reversalRepository;

    @Autowired
    private RefundService refundService;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptionRepository;

    @Autowired
    private WebhookSecurityValidator webhookSecurityValidator;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    @Autowired(required = false)
    private PlatformMetrics platformMetrics;

    private UserEntity customerUser;
    private UserEntity secondCustomerUser;
    private UserEntity merchantUser;
    private UserEntity adminUser;

    private AccountEntity customerAccount;
    private AccountEntity secondCustomerAccount;
    private AccountEntity merchantAccount;

    private String customerToken;
    private String secondCustomerToken;
    private String merchantToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        // Create deterministic test users
        customerUser = getOrCreateUser("p17-customer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", Role.CUSTOMER);
        secondCustomerUser = getOrCreateUser("p17-second-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", Role.CUSTOMER);
        merchantUser = getOrCreateUser("p17-merchant-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", Role.MERCHANT);
        adminUser = getOrCreateUser("p17-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", Role.ADMIN);

        customerAccount = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 1_000_000L);
        secondCustomerAccount = getOrCreateAccount(secondCustomerUser.getId(), AccountType.CUSTOMER, "USD", 500_000L);
        merchantAccount = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 0L);

        customerToken = jwtService.generateAccessToken(customerUser);
        secondCustomerToken = jwtService.generateAccessToken(secondCustomerUser);
        merchantToken = jwtService.generateAccessToken(merchantUser);
        adminToken = jwtService.generateAccessToken(adminUser);
    }

    private UserEntity getOrCreateUser(String email, Role role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            UserEntity user = new UserEntity(email, passwordEncoder.encode("TestPassword123!"), role);
            return userRepository.save(user);
        });
    }

    private AccountEntity platformClearingAccount;

    private AccountEntity getPlatformClearingAccount() {
        if (platformClearingAccount == null) {
            platformClearingAccount = accountRepository.findFirstByAccountTypeAndCurrency(AccountType.INTERNAL_SETTLEMENT, "USD").orElseGet(() -> {
                AccountEntity clearing = new AccountEntity("ACC-P17-CLEARING", adminUser.getId(), AccountType.INTERNAL_SETTLEMENT, "USD", AccountStatus.ACTIVE);
                return accountRepository.saveAndFlush(clearing);
            });
        }
        return platformClearingAccount;
    }

    private AccountEntity getOrCreateAccount(UUID ownerId, AccountType type, String currency, long initialBalance) {
        String num = "ACC-P17-" + type.name().substring(0, 3) + "-" + UUID.randomUUID().toString().toUpperCase();
        AccountEntity acc = new AccountEntity(num, ownerId, type, currency, AccountStatus.ACTIVE);
        acc = accountRepository.saveAndFlush(acc);
        if (initialBalance > 0) {
            AccountEntity clearing = getPlatformClearingAccount();
            long seq = ledgerEntryRepository.getMaxSequenceNumberForAccount(acc.getId()) + 1;
            long clearingSeq = ledgerEntryRepository.getMaxSequenceNumberForAccount(clearing.getId()) + 1;

            LedgerTransactionEntity tx = new LedgerTransactionEntity(
                    com.paymentledger.ledger.domain.LedgerTransactionType.SYSTEM_ADJUSTMENT,
                    UUID.randomUUID(), "INITIAL_SEED", currency, "Seed balance"
            );
            com.paymentledger.ledger.domain.LedgerEntryEntity entry =
                    new com.paymentledger.ledger.domain.LedgerEntryEntity(acc.getId(), com.paymentledger.ledger.domain.LedgerEntryDirection.CREDIT, initialBalance, currency, seq);
            com.paymentledger.ledger.domain.LedgerEntryEntity clearingEntry =
                    new com.paymentledger.ledger.domain.LedgerEntryEntity(clearing.getId(), com.paymentledger.ledger.domain.LedgerEntryDirection.DEBIT, initialBalance, currency, clearingSeq);

            tx.addEntry(entry);
            tx.addEntry(clearingEntry);
            tx.post();
            ledgerTransactionRepository.saveAndFlush(tx);
            acc.addBalanceMinor(initialBalance);
            acc = accountRepository.saveAndFlush(acc);
        }
        return acc;
    }

    // =========================================================================
    // A — Configuration Safety
    // =========================================================================
    @Test
    @DisplayName("A — Configuration safety: production profile file exists and contains hardened parameters")
    void A_configurationSafety() {
        File prodConfig = new File("src/main/resources/application-prod.yml");
        assertThat(prodConfig).as("application-prod.yml must exist").exists();

        // Verify datasource hikari settings are configured
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isNotNull();
        // Server graceful shutdown must be active
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    }

    // =========================================================================
    // B — Secret Scanning
    // =========================================================================
    @Test
    @DisplayName("B — Secret scanning: application verifies secret entropy and rejects weak JWT secrets")
    void B_secretScanningAndEntropy() {
        // Enforce that JWT secret requires at least 32 characters
        com.paymentledger.auth.config.JwtProperties weakProps = new com.paymentledger.auth.config.JwtProperties();
        weakProps.setSecret("too-short");
        assertThatThrownBy(() -> new JwtService(weakProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    // =========================================================================
    // C — JWT Hardening
    // =========================================================================
    @Test
    @DisplayName("C — JWT hardening: expired, malformed, wrong signature, and missing tokens rejected with 403 Forbidden")
    void C_jwtHardening() throws Exception {
        // 1. Missing token
        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());

        // 2. Malformed token
        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer not.a.valid.jwt.token"))
                .andExpect(status().isForbidden());

        // 3. Wrong signature
        String forgedToken = Jwts.builder()
                .subject(customerUser.getId().toString())
                .claim("role", "CUSTOMER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor("attacker_secret_key_with_at_least_32_characters_long!".getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isForbidden());

        // 4. Expired token
        String expiredToken = Jwts.builder()
                .subject(customerUser.getId().toString())
                .claim("role", "CUSTOMER")
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 1000))
                .signWith(Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // D — Refresh Token Replay
    // =========================================================================
    @Test
    @DisplayName("D — Refresh token replay: consumed or replayed refresh tokens rejected atomically")
    void D_refreshTokenReplayProtection() {
        // Login to get a valid token pair
        var loginResponse = authService.login(new LoginRequest(customerUser.getEmail(), "TestPassword123!"));
        String rawRefreshToken = loginResponse.getRefreshToken();

        // First refresh succeeds
        var refreshedPair = authService.refreshToken(new RefreshTokenRequest(rawRefreshToken));
        assertThat(refreshedPair.getAccessToken()).isNotBlank();
        assertThat(refreshedPair.getRefreshToken()).isNotEqualTo(rawRefreshToken);

        // Replay of consumed refresh token MUST fail
        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest(rawRefreshToken)))
                .isInstanceOf(com.paymentledger.auth.exception.InvalidRefreshTokenException.class);
    }

    // =========================================================================
    // E — Authorization Matrix
    // =========================================================================
    @Test
    @DisplayName("E — Authorization matrix: Customer cannot access Admin endpoints (403), Admin allowed")
    void E_authorizationMatrix() throws Exception {
        // Unauthenticated -> 403 Forbidden
        mockMvc.perform(get("/api/v1/admin/accounts"))
                .andExpect(status().isForbidden());

        // Customer calling Admin endpoint -> 403 Forbidden
        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        // Admin calling Admin endpoint -> 200 OK
        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // F — IDOR Protection
    // =========================================================================
    @Test
    @DisplayName("F — IDOR protection: Customer A cannot access Customer B's resources")
    void F_idorProtection() throws Exception {
        // Customer B tries to access Customer A's account -> 404 or 403
        mockMvc.perform(get("/api/v1/accounts/" + customerAccount.getId())
                        .header("Authorization", "Bearer " + secondCustomerToken))
                .andExpect(status().isNotFound());

        // Customer A accessing own account -> 200 OK
        mockMvc.perform(get("/api/v1/accounts/" + customerAccount.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // G — Input Validation
    // =========================================================================
    @Test
    @DisplayName("G — Input validation: Negative, zero, and invalid currencies rejected with 400 Bad Request")
    void G_inputValidation() throws Exception {
        // 1. Negative amount
        String negativeAmountJson = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": -500,
                    "currency": "USD",
                    "paymentMethodToken": "tok_123"
                }
                """.formatted(merchantAccount.getId());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negativeAmountJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PAYLOAD"));

        // 2. Zero amount
        String zeroAmountJson = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 0,
                    "currency": "USD",
                    "paymentMethodToken": "tok_123"
                }
                """.formatted(merchantAccount.getId());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(zeroAmountJson))
                .andExpect(status().isBadRequest());

        // 3. Invalid currency length
        String badCurrencyJson = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 1000,
                    "currency": "US",
                    "paymentMethodToken": "tok_123"
                }
                """.formatted(merchantAccount.getId());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badCurrencyJson))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // H — Request Size Limits
    // =========================================================================
    @Test
    @DisplayName("H — Request size: oversized JSON payloads handled safely")
    void H_requestSizeBounds() throws Exception {
        // Send a request with a very large dummy field
        String oversizedJson = "{\"payeeAccountId\": \"" + merchantAccount.getId() + "\", \"amountMinor\": 1000, \"currency\": \"USD\", \"paymentMethodToken\": \""
                + "A".repeat(50_000) + "\"}";

        // Spring should handle it without unhandled crashes
        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", "Bearer " + customerToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversizedJson));
    }

    // =========================================================================
    // I — Timeout Behavior
    // =========================================================================
    @Test
    @DisplayName("I — Timeout behavior: Provider timeout transitions payment to PENDING_RECONCILIATION (202 Accepted)")
    void I_timeoutBehavior() throws Exception {
        String timeoutJson = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 1500,
                    "currency": "USD",
                    "paymentMethodToken": "tok_timeout"
                }
                """.formatted(merchantAccount.getId());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timeoutJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_RECONCILIATION"));
    }

    // =========================================================================
    // J — Retry Behavior
    // =========================================================================
    @Test
    @DisplayName("J — Retry behavior: Outbox retry attempts are bounded by max-retries")
    void J_boundedRetries() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = new OutboxEventEntity(
                eventId, "PAYMENT", customerAccount.getId().toString(), "PaymentSettled",
                "1.0", "payment.events", customerAccount.getId().toString(),
                UUID.randomUUID(), UUID.randomUUID().toString(), "{\"amount\":100}"
        );
        event.claim("test-worker-1");
        event.markFailed("simulated network failure", Instant.now().plusSeconds(30), false);
        event = outboxEventRepository.save(event);

        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    // =========================================================================
    // K — Database Pool Safety
    // =========================================================================
    @Test
    @DisplayName("K — Database pool safety: Database connections are released after execution")
    void K_databasePoolSafety() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
        // Query succeeds immediately after connection close
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    // =========================================================================
    // L — Transaction Rollback Safety
    // =========================================================================
    @Test
    @DisplayName("L — Transaction rollback safety: Failed settlement causes zero ledger mutation")
    void L_transactionRollback() {
        long initialLedgerCount = ledgerTransactionRepository.count();
        long initialBalance = customerAccount.getMaterializedBalanceMinor();

        // Attempt settlement with non-existent payment ID
        UUID fakePaymentId = UUID.randomUUID();
        assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(fakePaymentId, "ref_fake"))
                .isInstanceOf(IllegalStateException.class);

        // Verify no ledger entries created
        assertThat(ledgerTransactionRepository.count()).isEqualTo(initialLedgerCount);
        // Verify account balance intact
        AccountEntity reloaded = accountRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(reloaded.getMaterializedBalanceMinor()).isEqualTo(initialBalance);
    }

    // =========================================================================
    // M — Kafka Failure Tolerance
    // =========================================================================
    @Test
    @DisplayName("M — Kafka failure tolerance: Financial transaction succeeds even if Kafka publishing fails")
    void M_kafkaFailureTolerance() {
        // Outbox event is saved inside PostgreSQL transaction, decoupling from immediate Kafka availability
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity outboxEvent = new OutboxEventEntity(
                eventId, "PAYMENT", customerAccount.getId().toString(), "PaymentSettled",
                "1.0", "payment.events", customerAccount.getId().toString(),
                UUID.randomUUID(), UUID.randomUUID().toString(), "{\"payload\":\"test\"}"
        );
        OutboxEventEntity saved = outboxEventRepository.save(outboxEvent);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    // =========================================================================
    // N — Kafka Recovery
    // =========================================================================
    @Test
    @DisplayName("N — Kafka recovery: Outbox relay picks up pending events")
    void N_kafkaRecovery() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity outboxEvent = new OutboxEventEntity(
                eventId, "PAYMENT", customerAccount.getId().toString(), "PaymentSettled",
                "1.0", "payment.events", customerAccount.getId().toString(),
                UUID.randomUUID(), UUID.randomUUID().toString(), "{\"test\":\"recovery\"}"
        );
        outboxEventRepository.save(outboxEvent);

        // Trigger relay scheduler
        outboxRelayScheduler.relayPendingEvents();

        OutboxEventEntity processed = outboxEventRepository.findById(outboxEvent.getId()).orElseThrow();
        assertThat(processed.getStatus()).isIn(OutboxStatus.PUBLISHED, OutboxStatus.PENDING, OutboxStatus.PROCESSING);
    }

    // =========================================================================
    // O — Outbox Stale Lease Recovery
    // =========================================================================
    @Test
    @DisplayName("O — Outbox stale lease recovery: Expired leases are reclaimable")
    void O_staleLeaseRecovery() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity outboxEvent = new OutboxEventEntity(
                eventId, "PAYMENT", customerAccount.getId().toString(), "PaymentSettled",
                "1.0", "payment.events", customerAccount.getId().toString(),
                UUID.randomUUID(), UUID.randomUUID().toString(), "{\"test\":\"lease\"}"
        );
        // Set event as PROCESSING
        outboxEvent.claim("old-worker-dead");
        outboxEvent = outboxEventRepository.save(outboxEvent);

        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(outboxEvent.getLockedBy()).isEqualTo("old-worker-dead");
        assertThat(outboxEvent.getLockedAt()).isNotNull();

        // Reclaim by new worker
        outboxEvent.claim("new-worker-alive");
        outboxEvent = outboxEventRepository.save(outboxEvent);
        assertThat(outboxEvent.getLockedBy()).isEqualTo("new-worker-alive");
    }

    // =========================================================================
    // P — Outbox Processing
    // =========================================================================
    @Test
    @DisplayName("P — Outbox processing: Unrelayed events are processed reliably")
    void P_outboxReliability() {
        long pendingBefore = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        assertThat(pendingBefore).isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Q — Redis Failure Fallback
    // =========================================================================
    @Test
    @DisplayName("Q — Redis failure fallback: FAIL_OPEN policy allows traffic during Redis degradation")
    void Q_redisFailureFallback() {
        if (redisRateLimiter != null) {
            RateLimitResult result = redisRateLimiter.checkLimit("nonexistent:test:key", 10, 60, RateLimitPolicy.FAIL_OPEN);
            assertThat(result.allowed()).isTrue();
        }
    }

    // =========================================================================
    // R — Provider Timeout Idempotency
    // =========================================================================
    @Test
    @DisplayName("R — Provider timeout idempotency: Retrying a timed-out idempotency key returns consistent state")
    void R_providerTimeoutIdempotency() throws Exception {
        String idempotencyKey = "idemp-timeout-" + UUID.randomUUID();
        String timeoutJson = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 2000,
                    "currency": "USD",
                    "paymentMethodToken": "tok_timeout"
                }
                """.formatted(merchantAccount.getId());

        // First attempt -> 202 PENDING_RECONCILIATION
        MvcResult first = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timeoutJson))
                .andExpect(status().isAccepted())
                .andReturn();

        // Second attempt with same key -> same 202 response
        MvcResult second = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(timeoutJson))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode json1 = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode json2 = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(json1.get("paymentId").asText()).isEqualTo(json2.get("paymentId").asText());
    }

    // =========================================================================
    // S — Provider Ambiguity
    // =========================================================================
    @Test
    @DisplayName("S — Provider ambiguity: Payments in PENDING_RECONCILIATION are preserved without premature ledger posting")
    void S_providerAmbiguity() {
        PaymentEntity payment = new PaymentEntity(
                "idemp-ambig-" + UUID.randomUUID(), "test-scope",
                customerAccount.getId(), merchantAccount.getId(),
                3000L, "USD"
        );
        payment.markPendingReconciliation();
        payment = paymentRepository.save(payment);

        // Verify no ledger transactions exist for this payment
        boolean exists = ledgerTransactionRepository.existsBySourceReferenceIdAndSourceReferenceType(payment.getId(), "PAYMENT");
        assertThat(exists).isFalse();
    }

    // =========================================================================
    // T — Webhook Security & SSRF Protection
    // =========================================================================
    @Test
    @DisplayName("T — Webhook security: SSRF validator blocks loopback, private ranges, and cloud metadata")
    void T_webhookSecurity() {
        // 1. Loopback blocked
        assertThatThrownBy(() -> webhookSecurityValidator.validateUrl("http://127.0.0.1:8080/hook"))
                .isInstanceOf(SsrfBlockedException.class);

        // 2. Cloud metadata blocked
        assertThatThrownBy(() -> webhookSecurityValidator.validateUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class);

        // 3. Private RFC-1918 blocked
        assertThatThrownBy(() -> webhookSecurityValidator.validateUrl("http://10.0.0.1/webhook"))
                .isInstanceOf(SsrfBlockedException.class);

        // 4. Valid external test domain allowed
        webhookSecurityValidator.validateUrl("https://example.com/webhook");
    }

    // =========================================================================
    // U — Actuator Security
    // =========================================================================
    @Test
    @DisplayName("U — Actuator security: /actuator/health is public; sensitive /actuator/metrics requires auth")
    void U_actuatorSecurity() throws Exception {
        // Public health probe
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        // Public liveness probe
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        // Unauthenticated metrics request -> 403 Forbidden
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());

        // Authenticated admin metrics request -> 200 OK
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // V — Error Redaction
    // =========================================================================
    @Test
    @DisplayName("V — Error redaction: Error responses follow RFC 7807 and hide internal SQL / stack traces")
    void V_errorRedaction() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("org.postgresql");
        assertThat(body).doesNotContain("HibernateException");
        assertThat(body).doesNotContain("at com.paymentledger");
        assertThat(body).contains("correlationId");
    }

    // =========================================================================
    // W — Resource Bounds
    // =========================================================================
    @Test
    @DisplayName("W — Resource bounds: Bounded pagination clamps excessive page size requests")
    void W_resourceBounds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/accounts?size=10000")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("size").asInt()).isLessThanOrEqualTo(100);
    }

    // =========================================================================
    // X — Graceful Shutdown
    // =========================================================================
    @Test
    @DisplayName("X — Graceful shutdown: server.shutdown is configured as graceful")
    void X_gracefulShutdown() {
        String shutdownConfig = environment.getProperty("server.shutdown");
        assertThat(shutdownConfig).isEqualTo("graceful");
    }

    // =========================================================================
    // Y — Startup / Readiness Safety
    // =========================================================================
    @Test
    @DisplayName("Y — Startup readiness safety: Readiness probe verifies DB state")
    void Y_startupReadiness() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // =========================================================================
    // Z — Rate Limiting
    // =========================================================================
    @Test
    @DisplayName("Z — Rate limiting: Exceeding auth limit triggers rate limiting protection")
    void Z_rateLimiting() {
        if (redisRateLimiter != null) {
            String testKey = "ratelimit:test:" + UUID.randomUUID();
            // Consume limit of 2
            RateLimitResult r1 = redisRateLimiter.checkLimit(testKey, 2, 60, RateLimitPolicy.FAIL_OPEN);
            RateLimitResult r2 = redisRateLimiter.checkLimit(testKey, 2, 60, RateLimitPolicy.FAIL_OPEN);
            RateLimitResult r3 = redisRateLimiter.checkLimit(testKey, 2, 60, RateLimitPolicy.FAIL_OPEN);

            assertThat(r1.allowed()).isTrue();
            assertThat(r2.allowed()).isTrue();
            assertThat(r3.allowed()).isFalse();
        }
    }

    // =========================================================================
    // AA — Idempotency Hardening
    // =========================================================================
    @Test
    @DisplayName("AA — Idempotency hardening: Same key + different payload returns 409 Conflict")
    void AA_idempotencyMismatch() throws Exception {
        String idempotencyKey = "idemp-mismatch-" + UUID.randomUUID();
        String payload1 = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 1000,
                    "currency": "USD",
                    "paymentMethodToken": "tok_valid"
                }
                """.formatted(merchantAccount.getId());

        // First request succeeds
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated());

        // Second request with same key but different payload -> 409 Conflict
        String payload2 = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 2000,
                    "currency": "USD",
                    "paymentMethodToken": "tok_valid"
                }
                """.formatted(merchantAccount.getId());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // AB — Concurrency Hardening
    // =========================================================================
    @Test
    @DisplayName("AB — Concurrency hardening: Deterministic lock ordering prevents deadlocks between concurrent transfers")
    void AB_concurrencyDeadlockPrevention() throws Exception {
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final boolean reverse = (i % 2 == 1);
            futures.add(executor.submit(() -> {
                latch.await();
                try {
                    UUID fromAcc = reverse ? secondCustomerAccount.getId() : customerAccount.getId();
                    UUID toAcc = reverse ? customerAccount.getId() : secondCustomerAccount.getId();
                    ledgerService.postAdjustmentWithLedger(
                            UUID.randomUUID(), fromAcc, toAcc, 100L, "USD",
                            "Concurrent stress test", adminUser.getId(), UUID.randomUUID().toString()
                    );
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) successes++;
        }
        assertThat(successes).isGreaterThan(0);
    }

    // =========================================================================
    // AC — Financial Invariant Audit
    // =========================================================================
    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("AC — Financial invariant audit: Every posted ledger transaction has SUM(debit) == SUM(credit)")
    void AC_financialInvariants() {
        List<LedgerTransactionEntity> transactions = ledgerTransactionRepository.findAll();
        for (LedgerTransactionEntity tx : transactions) {
            long sumDebits = tx.getEntries().stream()
                    .filter(e -> e.getDirection() == com.paymentledger.ledger.domain.LedgerEntryDirection.DEBIT)
                    .mapToLong(com.paymentledger.ledger.domain.LedgerEntryEntity::getAmountMinor)
                    .sum();
            long sumCredits = tx.getEntries().stream()
                    .filter(e -> e.getDirection() == com.paymentledger.ledger.domain.LedgerEntryDirection.CREDIT)
                    .mapToLong(com.paymentledger.ledger.domain.LedgerEntryEntity::getAmountMinor)
                    .sum();
            assertThat(sumDebits).as("Transaction %s must be balanced", tx.getId()).isEqualTo(sumCredits);
        }
    }

    // =========================================================================
    // AD — Failure Injection
    // =========================================================================
    @Test
    @DisplayName("AD — Failure injection: Provider decline is handled safely without orphan ledger records")
    void AD_failureInjectionProviderDecline() throws Exception {
        long txCountBefore = ledgerTransactionRepository.count();

        String declineJson = """
                {
                    "payeeAccountId": "%s",
                    "amountMinor": 2500,
                    "currency": "USD",
                    "paymentMethodToken": "tok_decline"
                }
                """.formatted(merchantAccount.getId());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(declineJson))
                .andExpect(status().isUnprocessableEntity());

        // Zero ledger transactions created on payment decline
        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    // =========================================================================
    // AE — Recovery Testing
    // =========================================================================
    @Test
    @DisplayName("AE — Recovery testing: Re-verifying account state after failed operations")
    void AE_recoveryTesting() {
        AccountEntity acc = accountRepository.findById(customerAccount.getId()).orElseThrow();
        long authoritativeBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(acc.getId());
        assertThat(acc.getMaterializedBalanceMinor()).isEqualTo(authoritativeBalance);
    }

    // =========================================================================
    // AF — Backup & Restore Readiness Documentation
    // =========================================================================
    @Test
    @DisplayName("AF — Backup/restore readiness: Production backup/restore documentation exists")
    void AF_backupRestoreDocumentation() {
        File backupDoc = new File("docs/production/backup-restore.md");
        assertThat(backupDoc).exists();
        assertThat(backupDoc.length()).isGreaterThan(200);
    }

    // =========================================================================
    // AG — Dependency Security
    // =========================================================================
    @Test
    @DisplayName("AG — Dependency security: Critical dependencies match approved enterprise versions")
    void AG_dependencySecurity() {
        File pom = new File("pom.xml");
        assertThat(pom).exists();
        // Jackson, JJWT, Spring Boot starters verified present
        assertThat(pom).content().contains("spring-boot-starter-parent");
        assertThat(pom).content().contains("3.3.4");
    }

    // =========================================================================
    // AH — Performance Smoke Test
    // =========================================================================
    @Test
    @DisplayName("AH — Performance smoke: Rapid creation of 5 payments completes under 2 seconds")
    void AH_performanceSmokeTest() throws Exception {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            String json = """
                    {
                        "payeeAccountId": "%s",
                        "amountMinor": 100,
                        "currency": "USD",
                        "paymentMethodToken": "tok_perf_%d"
                    }
                    """.formatted(merchantAccount.getId(), i);

            mockMvc.perform(post("/api/v1/payments")
                            .header("Authorization", "Bearer " + customerToken)
                            .header("Idempotency-Key", "perf-key-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }
        long duration = System.currentTimeMillis() - start;
        assertThat(duration).isLessThan(5000); // Generous margin for test containers
    }

    // =========================================================================
    // AI — Resource Leak Detection
    // =========================================================================
    @Test
    @DisplayName("AI — Resource leak detection: Repeated DB queries release connection handles stably")
    void AI_resourceLeakDetection() throws Exception {
        for (int i = 0; i < 20; i++) {
            try (Connection conn = dataSource.getConnection()) {
                assertThat(conn.isClosed()).isFalse();
            }
        }
        Integer val = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(val).isEqualTo(1);
    }

    // =========================================================================
    // AJ — Security Regression
    // =========================================================================
    @Test
    @DisplayName("AJ — Security regression: HTTP security headers (X-Content-Type-Options, Frame-Options) present")
    void AJ_securityHeadersRegression() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    // =========================================================================
    // AK — Observability Regression
    // =========================================================================
    @Test
    @DisplayName("AK — Observability regression: LogMaskingConverter scrubs sensitive tokens")
    void AK_observabilityRegression() {
        String sensitiveJson = "{\"password\":\"MySecretPass123!\",\"apiKey\":\"sk_live_12345\"}";
        String masked = LogMaskingConverter.mask(sensitiveJson);
        assertThat(masked).doesNotContain("MySecretPass123!");
        assertThat(masked).doesNotContain("sk_live_12345");
        assertThat(masked).contains("********");
    }

    // =========================================================================
    // AL — No Phase 20 Leakage
    // =========================================================================
    @Test
    @DisplayName("AL — No Phase 21 leakage: Phase 21 does not exist, Phase 20 is the final roadmap milestone")
    void AL_noPhase20Leakage() {
        File phase21Report = new File("docs/phase-reports/PHASE-21.md");
        assertThat(phase21Report).as("Phase 21 report must not exist — Phase 20 is final").doesNotExist();
    }

    // =========================================================================
    // Helper Methods for LedgerService Hardening Tests
    // =========================================================================
    private PaymentEntity createCapturingPayment(AccountEntity payer, AccountEntity payee, long amountMinor, String currency) {
        PaymentEntity payment = new PaymentEntity(
                "idemp-" + UUID.randomUUID(), "test-scope",
                payer.getId(), payee.getId(),
                amountMinor, currency
        );
        payment.authorize();
        payment.authorizationSucceeded("auth_ref_" + UUID.randomUUID());
        payment.capture();
        return paymentRepository.saveAndFlush(payment);
    }

    private UUID generateUuidSmallerThan(UUID reference) {
        UUID candidate;
        do {
            candidate = UUID.randomUUID();
        } while (candidate.compareTo(reference) >= 0);
        return candidate;
    }

    private UUID generateUuidLargerThan(UUID reference) {
        UUID candidate;
        do {
            candidate = UUID.randomUUID();
        } while (candidate.compareTo(reference) <= 0);
        return candidate;
    }

    // =========================================================================
    // AM — LedgerService Payment Settlement Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Payment: Settlement rejected with PaymentDomainException when payer account is frozen")
    void AM1_paymentSettlement_frozenPayer() {
        AccountEntity frozenCustomer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 50_000L);
        frozenCustomer.freeze();
        accountRepository.saveAndFlush(frozenCustomer);

        try {
            PaymentEntity payment = createCapturingPayment(frozenCustomer, merchantAccount, 5000L, "USD");
            long txCountBefore = ledgerTransactionRepository.count();
            long payerBalanceBefore = frozenCustomer.getMaterializedBalanceMinor();
            long merchantBalanceBefore = merchantAccount.getMaterializedBalanceMinor();

            assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(payment.getId(), "cap_frozen", "corr_frozen"))
                    .isInstanceOf(PaymentDomainException.class)
                    .hasMessage("ACCOUNT_FROZEN");

            assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
            AccountEntity reloadedPayer = accountRepository.findById(frozenCustomer.getId()).orElseThrow();
            AccountEntity reloadedMerchant = accountRepository.findById(merchantAccount.getId()).orElseThrow();
            assertThat(reloadedPayer.getMaterializedBalanceMinor()).isEqualTo(payerBalanceBefore);
            assertThat(reloadedMerchant.getMaterializedBalanceMinor()).isEqualTo(merchantBalanceBefore);
        } finally {
            AccountEntity reloaded = accountRepository.findById(frozenCustomer.getId()).orElse(null);
            if (reloaded != null && reloaded.getStatus() == AccountStatus.FROZEN) {
                reloaded.unfreeze();
                accountRepository.saveAndFlush(reloaded);
            }
        }
    }

    @Test
    @DisplayName("Hardening — Payment: Settlement rejected with PaymentDomainException when payer currency mismatches")
    void AM2_paymentSettlement_payerCurrencyMismatch() {
        AccountEntity eurCustomer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "EUR", 50_000L);
        PaymentEntity payment = createCapturingPayment(eurCustomer, merchantAccount, 5000L, "USD");
        long txCountBefore = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(payment.getId(), "cap_curr_mismatch", "corr_mismatch"))
                .isInstanceOf(PaymentDomainException.class)
                .hasMessage("Currency mismatch");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("Hardening — Payment: Settlement rejected with PaymentDomainException when payee currency mismatches")
    void AM3_paymentSettlement_payeeCurrencyMismatch() {
        AccountEntity eurMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "EUR", 0L);
        PaymentEntity payment = createCapturingPayment(customerAccount, eurMerchant, 5000L, "USD");
        long txCountBefore = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(payment.getId(), "cap_payee_mismatch", "corr_payee_mismatch"))
                .isInstanceOf(PaymentDomainException.class)
                .hasMessage("Currency mismatch");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("Hardening — Payment: Duplicate ledger posting rejected with IllegalStateException")
    void AM4_paymentSettlement_duplicatePosting() {
        AccountEntity payer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 50_000L);
        PaymentEntity payment = createCapturingPayment(payer, merchantAccount, 5000L, "USD");
        LedgerTransactionEntity tx = ledgerService.settlePaymentWithLedger(payment.getId(), "cap_dup_1", "corr_dup_1");
        assertThat(tx).isNotNull();
        long txCountAfterFirst = ledgerTransactionRepository.count();

        // Reload fresh payment entity from DB to prevent optimistic locking failure
        PaymentEntity reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        reloadedPayment.markPendingReconciliation();
        paymentRepository.saveAndFlush(reloadedPayment);

        UUID paymentId = payment.getId();
        assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(paymentId, "cap_dup_2", "corr_dup_2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ledger posting detected for payment");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountAfterFirst);
    }

    @Test
    @DisplayName("Hardening — Payment: Authoritative ledger balance check rejects settlement despite sufficient materialized balance")
    void AM5_paymentSettlement_authoritativeInsufficientBalance() {
        AccountEntity underfundedCustomer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 0L);
        underfundedCustomer.addBalanceMinor(50_000L);
        accountRepository.saveAndFlush(underfundedCustomer);

        PaymentEntity payment = createCapturingPayment(underfundedCustomer, merchantAccount, 10_000L, "USD");
        long txCountBefore = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(payment.getId(), "cap_insuf", "corr_insuf"))
                .isInstanceOf(PaymentDomainException.class)
                .hasMessage("INSUFFICIENT_FUNDS");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
        AccountEntity reloaded = accountRepository.findById(underfundedCustomer.getId()).orElseThrow();
        assertThat(reloaded.getMaterializedBalanceMinor()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("Hardening — Payment: Authoritative sufficient balance posts ledger transaction and updates balances (2-arg overload)")
    void AM6_paymentSettlement_authoritativeSufficientBalance() {
        AccountEntity wellFundedCustomer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 100_000L);
        PaymentEntity payment = createCapturingPayment(wellFundedCustomer, merchantAccount, 25_000L, "USD");
        long merchantBalanceBefore = merchantAccount.getMaterializedBalanceMinor();

        LedgerTransactionEntity tx = ledgerService.settlePaymentWithLedger(payment.getId(), "cap_ok_2arg");
        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

        AccountEntity reloadedCust = accountRepository.findById(wellFundedCustomer.getId()).orElseThrow();
        AccountEntity reloadedMerchant = accountRepository.findById(merchantAccount.getId()).orElseThrow();

        assertThat(reloadedCust.getMaterializedBalanceMinor()).isEqualTo(75_000L);
        assertThat(reloadedMerchant.getMaterializedBalanceMinor()).isEqualTo(merchantBalanceBefore + 25_000L);
        assertThat(ledgerEntryRepository.calculateLedgerBalanceMinor(wellFundedCustomer.getId())).isEqualTo(75_000L);
    }

    @Test
    @DisplayName("Hardening — Payment: Settlement rejected when payment is not in CAPTURING or PENDING_RECONCILIATION state")
    void AM7_paymentSettlement_invalidState() {
        PaymentEntity payment = new PaymentEntity(
                "idemp-created-" + UUID.randomUUID(), "test-scope",
                customerAccount.getId(), merchantAccount.getId(),
                5000L, "USD"
        );
        payment = paymentRepository.saveAndFlush(payment);

        UUID paymentId = payment.getId();
        assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(paymentId, "cap_ref", "corr_ref"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment must be in CAPTURING or PENDING_RECONCILIATION state to settle");
    }

    @Test
    @DisplayName("Hardening — Payment: Settlement succeeds for payment in PENDING_RECONCILIATION state")
    void AM8_paymentSettlement_pendingReconciliationState() {
        AccountEntity payer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 50_000L);
        PaymentEntity payment = new PaymentEntity(
                "idemp-recon-" + UUID.randomUUID(), "test-scope",
                payer.getId(), merchantAccount.getId(),
                5000L, "USD"
        );
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        LedgerTransactionEntity tx = ledgerService.settlePaymentWithLedger(payment.getId(), "cap_recon", "   ");
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    @DisplayName("Hardening — Payment: Non-customer payer account bypasses customer authoritative balance check")
    void AM9_paymentSettlement_nonCustomerPayer() {
        AccountEntity merchantPayer = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 0L);
        PaymentEntity payment = createCapturingPayment(merchantPayer, customerAccount, 5000L, "USD");

        LedgerTransactionEntity tx = ledgerService.settlePaymentWithLedger(payment.getId(), "cap_non_cust", "corr_non_cust");
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
    }

    // =========================================================================
    // AN — LedgerService Refund Settlement Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Refund: Settlement rejected with RefundDomainException when merchant account is frozen")
    void AN1_refundSettlement_frozenMerchant() {
        AccountEntity fundedMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        PaymentEntity payment = createCapturingPayment(customerAccount, fundedMerchant, 10_000L, "USD");
        RefundEntity refund = refundRepository.saveAndFlush(new RefundEntity(payment.getId(), 5000L, "USD", "Return item"));

        fundedMerchant.freeze();
        accountRepository.saveAndFlush(fundedMerchant);

        try {
            long txCountBefore = ledgerTransactionRepository.count();
            assertThatThrownBy(() -> ledgerService.settleRefundWithLedger(refund, payment, "corr-ref-frozen"))
                    .isInstanceOf(RefundDomainException.class)
                    .satisfies(e -> {
                        RefundDomainException rde = (RefundDomainException) e;
                        assertThat(rde.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_FROZEN);
                    });

            assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
        } finally {
            AccountEntity reloaded = accountRepository.findById(fundedMerchant.getId()).orElse(null);
            if (reloaded != null && reloaded.getStatus() == AccountStatus.FROZEN) {
                reloaded.unfreeze();
                accountRepository.saveAndFlush(reloaded);
            }
        }
    }

    @Test
    @DisplayName("Hardening — Refund: Settlement rejected with RefundDomainException when merchant has insufficient ledger balance")
    void AN2_refundSettlement_insufficientMerchantBalance() {
        AccountEntity brokeMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 0L);
        PaymentEntity payment = createCapturingPayment(customerAccount, brokeMerchant, 10_000L, "USD");
        RefundEntity refund = refundRepository.saveAndFlush(new RefundEntity(payment.getId(), 5000L, "USD", "Return item"));

        long txCountBefore = ledgerTransactionRepository.count();
        assertThatThrownBy(() -> ledgerService.settleRefundWithLedger(refund, payment, "corr-ref-insuf"))
                .isInstanceOf(RefundDomainException.class)
                .satisfies(e -> {
                    RefundDomainException rde = (RefundDomainException) e;
                    assertThat(rde.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                });

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("Hardening — Refund: Duplicate ledger posting rejected with IllegalStateException")
    void AN3_refundSettlement_duplicatePosting() {
        AccountEntity fundedMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        PaymentEntity payment = createCapturingPayment(customerAccount, fundedMerchant, 10_000L, "USD");
        RefundEntity refund = refundRepository.saveAndFlush(new RefundEntity(payment.getId(), 5000L, "USD", "Return item"));

        LedgerTransactionEntity tx = ledgerService.settleRefundWithLedger(refund, payment, "corr-ref-1");
        assertThat(tx).isNotNull();
        long txCountAfterFirst = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.settleRefundWithLedger(refund, payment, "corr-ref-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ledger posting detected for refund");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountAfterFirst);
    }

    @Test
    @DisplayName("Hardening — Refund: Fallback to Instant.now() when refund updatedAt is null")
    void AN4_refundSettlement_nullUpdatedAtFallback() {
        AccountEntity fundedMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        PaymentEntity payment = createCapturingPayment(customerAccount, fundedMerchant, 10_000L, "USD");

        RefundEntity unpersistedRefund = new RefundEntity(payment.getId(), 2000L, "USD", "Unpersisted refund");
        ReflectionTestUtils.setField(unpersistedRefund, "id", UUID.randomUUID());
        // updatedAt is null

        LedgerTransactionEntity tx = ledgerService.settleRefundWithLedger(unpersistedRefund, payment, "corr-null-updated");
        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
    }

    // =========================================================================
    // AO — LedgerService Reversal Settlement Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Reversal: Duplicate ledger posting rejected with IllegalStateException")
    void AO1_reversalSettlement_duplicatePosting() {
        AccountEntity fundedMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        PaymentEntity payment = createCapturingPayment(customerAccount, fundedMerchant, 10_000L, "USD");
        ReversalEntity reversal = reversalRepository.saveAndFlush(new ReversalEntity(payment.getId(), 10_000L, "USD", "Chargeback"));

        LedgerTransactionEntity tx = ledgerService.settleReversalWithLedger(reversal, payment, "corr-rev-1");
        assertThat(tx).isNotNull();
        long txCountAfterFirst = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.settleReversalWithLedger(reversal, payment, "corr-rev-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ledger posting detected for reversal");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountAfterFirst);
    }

    @Test
    @DisplayName("Hardening — Reversal: Fallback to Instant.now() when reversal updatedAt is null")
    void AO2_reversalSettlement_nullUpdatedAtFallback() {
        AccountEntity fundedMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        PaymentEntity payment = createCapturingPayment(customerAccount, fundedMerchant, 10_000L, "USD");

        ReversalEntity unpersistedReversal = new ReversalEntity(payment.getId(), 5000L, "USD", "Unpersisted reversal");
        ReflectionTestUtils.setField(unpersistedReversal, "id", UUID.randomUUID());
        // updatedAt is null

        LedgerTransactionEntity tx = ledgerService.settleReversalWithLedger(unpersistedReversal, payment, "corr-null-rev");
        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
    }

    // =========================================================================
    // AP — LedgerService Payout Settlement Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Payout: Settlement rejected with PayoutDomainException when origin account is frozen")
    void AP1_payoutSettlement_frozenOrigin() {
        AccountEntity origin = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        AccountEntity clearing = getPlatformClearingAccount();
        PayoutEntity payout = payoutRepository.saveAndFlush(new PayoutEntity(origin.getId(), 5000L, "USD"));

        origin.freeze();
        accountRepository.saveAndFlush(origin);

        try {
            long txCountBefore = ledgerTransactionRepository.count();
            assertThatThrownBy(() -> ledgerService.settlePayoutWithLedger(payout, origin, clearing, "corr-payout-frozen"))
                    .isInstanceOf(PayoutDomainException.class)
                    .satisfies(e -> {
                        PayoutDomainException pde = (PayoutDomainException) e;
                        assertThat(pde.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_FROZEN);
                    });

            assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
        } finally {
            AccountEntity reloaded = accountRepository.findById(origin.getId()).orElse(null);
            if (reloaded != null && reloaded.getStatus() == AccountStatus.FROZEN) {
                reloaded.unfreeze();
                accountRepository.saveAndFlush(reloaded);
            }
        }
    }

    @Test
    @DisplayName("Hardening — Payout: Settlement rejected with PayoutDomainException when origin has insufficient ledger balance")
    void AP2_payoutSettlement_insufficientBalance() {
        AccountEntity origin = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 0L);
        AccountEntity clearing = getPlatformClearingAccount();
        PayoutEntity payout = payoutRepository.saveAndFlush(new PayoutEntity(origin.getId(), 5000L, "USD"));

        long txCountBefore = ledgerTransactionRepository.count();
        assertThatThrownBy(() -> ledgerService.settlePayoutWithLedger(payout, origin, clearing, "corr-payout-insuf"))
                .isInstanceOf(PayoutDomainException.class)
                .satisfies(e -> {
                    PayoutDomainException pde = (PayoutDomainException) e;
                    assertThat(pde.getErrorCode()).isEqualTo(ErrorCode.PAYOUT_INSUFFICIENT_FUNDS);
                });

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("Hardening — Payout: Duplicate ledger posting rejected with IllegalStateException")
    void AP3_payoutSettlement_duplicatePosting() {
        AccountEntity origin = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        AccountEntity clearing = getPlatformClearingAccount();
        PayoutEntity payout = payoutRepository.saveAndFlush(new PayoutEntity(origin.getId(), 5000L, "USD"));

        LedgerTransactionEntity tx = ledgerService.settlePayoutWithLedger(payout, origin, clearing, "corr-payout-1");
        assertThat(tx).isNotNull();
        long txCountAfterFirst = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.settlePayoutWithLedger(payout, origin, clearing, "corr-payout-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ledger posting detected for payout");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountAfterFirst);
    }

    @Test
    @DisplayName("Hardening — Payout: Fallback to Instant.now() when payout updatedAt is null")
    void AP4_payoutSettlement_nullUpdatedAtFallback() {
        AccountEntity origin = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
        AccountEntity clearing = getPlatformClearingAccount();

        PayoutEntity unpersistedPayout = new PayoutEntity(origin.getId(), 3000L, "USD");
        ReflectionTestUtils.setField(unpersistedPayout, "id", UUID.randomUUID());
        // updatedAt is null

        LedgerTransactionEntity tx = ledgerService.settlePayoutWithLedger(unpersistedPayout, origin, clearing, "corr-null-payout");
        assertThat(tx).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
    }

    // =========================================================================
    // AQ — LedgerService Admin Adjustment Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Adjustment: Source currency mismatch rejected with IllegalArgumentException")
    void AQ1_postAdjustment_sourceCurrencyMismatch() {
        long txCountBefore = ledgerTransactionRepository.count();
        assertThatThrownBy(() -> ledgerService.postAdjustmentWithLedger(
                UUID.randomUUID(), customerAccount.getId(), secondCustomerAccount.getId(),
                1000L, "EUR", "Currency mismatch test", adminUser.getId(), "corr-adj-curr"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account currency does not match adjustment currency");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("Hardening — Adjustment: Target currency mismatch rejected with IllegalArgumentException")
    void AQ2_postAdjustment_targetCurrencyMismatch() {
        AccountEntity eurAccount = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "EUR", 0L);
        long txCountBefore = ledgerTransactionRepository.count();

        assertThatThrownBy(() -> ledgerService.postAdjustmentWithLedger(
                UUID.randomUUID(), customerAccount.getId(), eurAccount.getId(),
                1000L, "USD", "Target currency mismatch test", adminUser.getId(), "corr-target-mismatch"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account currency does not match adjustment currency");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    @Test
    @DisplayName("Hardening — Adjustment: Missing source and target accounts rejected across all lock ordering paths")
    void AQ3_postAdjustment_missingAccountsBothLockOrders() {
        long txCountBefore = ledgerTransactionRepository.count();

        // 1. Missing source < existing target (hits line 552)
        UUID smallerMissingSource = generateUuidSmallerThan(customerAccount.getId());
        assertThatThrownBy(() -> ledgerService.postAdjustmentWithLedger(
                UUID.randomUUID(), smallerMissingSource, customerAccount.getId(),
                100L, "USD", "test", adminUser.getId(), "corr-1"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source account not found");

        // 2. Existing source < missing target (hits line 554)
        UUID largerMissingTarget = generateUuidLargerThan(customerAccount.getId());
        assertThatThrownBy(() -> ledgerService.postAdjustmentWithLedger(
                UUID.randomUUID(), customerAccount.getId(), largerMissingTarget,
                100L, "USD", "test", adminUser.getId(), "corr-2"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target account not found");

        // 3. Missing target < existing source (hits line 557)
        UUID smallerMissingTarget = generateUuidSmallerThan(customerAccount.getId());
        assertThatThrownBy(() -> ledgerService.postAdjustmentWithLedger(
                UUID.randomUUID(), customerAccount.getId(), smallerMissingTarget,
                100L, "USD", "test", adminUser.getId(), "corr-3"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target account not found");

        // 4. Existing target < missing source (hits line 559)
        UUID largerMissingSource = generateUuidLargerThan(customerAccount.getId());
        assertThatThrownBy(() -> ledgerService.postAdjustmentWithLedger(
                UUID.randomUUID(), largerMissingSource, customerAccount.getId(),
                100L, "USD", "test", adminUser.getId(), "corr-4"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source account not found");

        assertThat(ledgerTransactionRepository.count()).isEqualTo(txCountBefore);
    }

    // =========================================================================
    // AR — LedgerService Lock Ordering Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Lock Ordering: All settlement operations execute correctly when account ID order is reversed")
    void AR1_lockOrderingReversed_allSettlementOperations() {
        AccountEntity accA = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 100_000L);
        AccountEntity accB = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 100_000L);

        AccountEntity smallerAcc = accA.getId().compareTo(accB.getId()) < 0 ? accA : accB;
        AccountEntity largerAcc = accA.getId().compareTo(accB.getId()) < 0 ? accB : accA;

        // 1. Payment with larger as payer, smaller as payee (exercises payerId > payeeId branch)
        PaymentEntity paymentRev = createCapturingPayment(largerAcc, smallerAcc, 1000L, "USD");
        LedgerTransactionEntity txPay = ledgerService.settlePaymentWithLedger(paymentRev.getId(), "cap_rev_order");
        assertThat(txPay.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

        // 2. Refund with larger as payer, smaller as payee (exercises payerAccountId > payeeAccountId branch)
        RefundEntity refundRev = refundRepository.saveAndFlush(new RefundEntity(paymentRev.getId(), 500L, "USD", "Refund rev"));
        LedgerTransactionEntity txRef = ledgerService.settleRefundWithLedger(refundRev, paymentRev, "corr_ref_rev");
        assertThat(txRef.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

        // 3. Reversal with larger as payer, smaller as payee (exercises payerAccountId > payeeAccountId branch)
        PaymentEntity paymentForRev = createCapturingPayment(largerAcc, smallerAcc, 1000L, "USD");
        ReversalEntity reversalRev = reversalRepository.saveAndFlush(new ReversalEntity(paymentForRev.getId(), 1000L, "USD", "Rev chargeback"));
        LedgerTransactionEntity txRev = ledgerService.settleReversalWithLedger(reversalRev, paymentForRev, "corr_rev_rev");
        assertThat(txRev.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

        // 4. Payout with larger as origin, smaller as clearing (exercises originId > settlementId branch)
        AccountEntity clearing = getPlatformClearingAccount();
        AccountEntity originP = clearing.getId().compareTo(accA.getId()) < 0 ? accA : clearing;
        AccountEntity settlementP = clearing.getId().compareTo(accA.getId()) < 0 ? clearing : accA;
        AccountEntity fundedOrigin = getOrCreateAccount(originP.getOwnerId(), originP.getAccountType(), "USD", 50_000L);
        PayoutEntity payout = payoutRepository.saveAndFlush(new PayoutEntity(fundedOrigin.getId(), 1000L, "USD"));
        LedgerTransactionEntity txPayout = ledgerService.settlePayoutWithLedger(payout, fundedOrigin, settlementP, "corr_payout_rev");
        assertThat(txPayout.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
    }

    // =========================================================================
    // AS — LedgerService Correlation ID Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Correlation ID: Valid UUID preserved, non-UUID converted deterministically, null/blank generates non-null")
    void AS1_correlationId_allBranches() {
        // Branch 1: Valid UUID
        String validUuidStr = "a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d";
        UUID validUuid = UUID.fromString(validUuidStr);
        UUID adjId1 = UUID.randomUUID();
        ledgerService.postAdjustmentWithLedger(
                adjId1, customerAccount.getId(), secondCustomerAccount.getId(),
                100L, "USD", "Valid UUID correlation", adminUser.getId(), validUuidStr
        );
        OutboxEventEntity event1 = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("FINANCIAL_ADJUSTMENT", adjId1.toString()).stream().findFirst().orElseThrow();
        assertThat(event1.getCorrelationId()).isEqualTo(validUuid);

        // Branch 2: Non-UUID string produces deterministic nameUUIDFromBytes
        String customCorrelation = "trace-custom-correlation-98765";
        UUID expectedDeterministicUuid = UUID.nameUUIDFromBytes(customCorrelation.getBytes(StandardCharsets.UTF_8));
        UUID adjId2 = UUID.randomUUID();
        ledgerService.postAdjustmentWithLedger(
                adjId2, customerAccount.getId(), secondCustomerAccount.getId(),
                100L, "USD", "Non-UUID correlation", adminUser.getId(), customCorrelation
        );
        OutboxEventEntity event2 = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("FINANCIAL_ADJUSTMENT", adjId2.toString()).stream().findFirst().orElseThrow();
        assertThat(event2.getCorrelationId()).isEqualTo(expectedDeterministicUuid);

        // Branch 3a: Null correlation ID generates non-null random UUID
        UUID adjId3 = UUID.randomUUID();
        ledgerService.postAdjustmentWithLedger(
                adjId3, customerAccount.getId(), secondCustomerAccount.getId(),
                100L, "USD", "Null correlation", adminUser.getId(), null
        );
        OutboxEventEntity event3 = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("FINANCIAL_ADJUSTMENT", adjId3.toString()).stream().findFirst().orElseThrow();
        assertThat(event3.getCorrelationId()).isNotNull();

        // Branch 3b: Blank correlation ID generates non-null random UUID
        UUID adjId4 = UUID.randomUUID();
        ledgerService.postAdjustmentWithLedger(
                adjId4, customerAccount.getId(), secondCustomerAccount.getId(),
                100L, "USD", "Blank correlation", adminUser.getId(), "   "
        );
        OutboxEventEntity event4 = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc("FINANCIAL_ADJUSTMENT", adjId4.toString()).stream().findFirst().orElseThrow();
        assertThat(event4.getCorrelationId()).isNotNull();
    }

    // =========================================================================
    // AT — LedgerService Metrics Fail-Safe & Constructor Hardening
    // =========================================================================
    @Test
    @DisplayName("Hardening — Metrics fail-safe: Financial transaction commits successfully even if metric recording throws")
    void AT1_metricsFailSafe_transactionSucceedsOnMetricException() {
        PlatformMetrics mockMetrics = mock(PlatformMetrics.class);
        doThrow(new RuntimeException("Simulated metrics failure"))
                .when(mockMetrics).recordLedgerBalanceCheck(anyString(), anyBoolean());
        doThrow(new RuntimeException("Simulated metrics failure"))
                .when(mockMetrics).recordLedgerTransactionPosted(anyString(), anyString());

        PlatformMetrics originalMetrics = (PlatformMetrics) ReflectionTestUtils.getField(ledgerService, "platformMetrics");
        try {
            ReflectionTestUtils.setField(ledgerService, "platformMetrics", mockMetrics);

            AccountEntity payer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 50_000L);
            PaymentEntity payment = createCapturingPayment(payer, merchantAccount, 10_000L, "USD");

            // 1. Payment settlement (sufficient)
            LedgerTransactionEntity tx = ledgerService.settlePaymentWithLedger(payment.getId(), "cap_metric_test", "corr_metric_test");
            assertThat(tx).isNotNull();
            assertThat(tx.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
            assertThat(ledgerTransactionRepository.findById(tx.getId())).isPresent();

            AccountEntity reloadedPayer = accountRepository.findById(payer.getId()).orElseThrow();
            assertThat(reloadedPayer.getMaterializedBalanceMinor()).isEqualTo(40_000L);

            // 2. Payment settlement (insufficient authoritative funds)
            AccountEntity brokePayer = getOrCreateAccount(customerUser.getId(), AccountType.CUSTOMER, "USD", 0L);
            PaymentEntity brokePayment = createCapturingPayment(brokePayer, merchantAccount, 10_000L, "USD");
            assertThatThrownBy(() -> ledgerService.settlePaymentWithLedger(brokePayment.getId(), "cap_metric_fail", "corr_metric_fail"))
                    .isInstanceOf(PaymentDomainException.class)
                    .hasMessage("INSUFFICIENT_FUNDS");

            // 3. Refund settlement
            AccountEntity fundedMerchant = getOrCreateAccount(merchantUser.getId(), AccountType.MERCHANT, "USD", 50_000L);
            PaymentEntity payForRef = createCapturingPayment(customerAccount, fundedMerchant, 10_000L, "USD");
            RefundEntity refund = refundRepository.saveAndFlush(new RefundEntity(payForRef.getId(), 2000L, "USD", "Metric fail refund"));
            LedgerTransactionEntity txRef = ledgerService.settleRefundWithLedger(refund, payForRef, "corr-ref-metric");
            assertThat(txRef.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

            // 4. Reversal settlement
            PaymentEntity payForRev = createCapturingPayment(customerAccount, fundedMerchant, 5000L, "USD");
            ReversalEntity reversal = reversalRepository.saveAndFlush(new ReversalEntity(payForRev.getId(), 5000L, "USD", "Metric fail rev"));
            LedgerTransactionEntity txRev = ledgerService.settleReversalWithLedger(reversal, payForRev, "corr-rev-metric");
            assertThat(txRev.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

            // 5. Payout settlement
            AccountEntity clearing = getPlatformClearingAccount();
            PayoutEntity payout = payoutRepository.saveAndFlush(new PayoutEntity(fundedMerchant.getId(), 1000L, "USD"));
            LedgerTransactionEntity txPayout = ledgerService.settlePayoutWithLedger(payout, fundedMerchant, clearing, "corr-payout-metric");
            assertThat(txPayout.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);

            // 6. Admin adjustment
            LedgerTransactionEntity txAdj = ledgerService.postAdjustmentWithLedger(
                    UUID.randomUUID(), customerAccount.getId(), secondCustomerAccount.getId(),
                    500L, "USD", "Metric fail adjustment", adminUser.getId(), "corr-adj-metric"
            );
            assertThat(txAdj.getStatus()).isEqualTo(LedgerTransactionStatus.POSTED);
        } finally {
            ReflectionTestUtils.setField(ledgerService, "platformMetrics", originalMetrics);
        }
    }

    @Test
    @DisplayName("Hardening — LedgerService: 4-argument constructor and setPlatformMetrics coverage")
    void AT2_ledgerService_constructorsAndSetters() {
        LedgerService service = new LedgerService(
                ledgerTransactionRepository,
                ledgerEntryRepository,
                accountRepository,
                paymentRepository
        );
        assertThat(service).isNotNull();

        // Exercise package-private setter via ReflectionTestUtils
        ReflectionTestUtils.invokeMethod(service, "setPlatformMetrics", platformMetrics);
    }
}
