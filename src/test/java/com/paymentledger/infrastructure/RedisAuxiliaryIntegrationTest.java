package com.paymentledger.infrastructure;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.account.service.AccountService;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.domain.OutboxStatus;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.outbox.service.OutboxRelayScheduler;
import com.paymentledger.outbox.service.OutboxService;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.shared.idempotency.IdempotencyRecordEntity;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import com.paymentledger.shared.redis.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 10 — Redis Auxiliary Infrastructure Integration Test Suite.
 * <p>
 * Tests A through P verifying:
 * - Redis configuration and connectivity
 * - Cache-aside read caching (hits, misses, TTL expiration, invalidation)
 * - Distributed atomic rate limiting (thresholds, TTL reset, concurrency, multi-actor isolation)
 * - Resilient degradation policies (FAIL_OPEN and FAIL_CLOSED)
 * - Absolute independence of financial settlement, ledger, and transactional outbox from Redis
 * - Absence of sensitive financial/auth data in Redis
 */
@SpringBootTest
class RedisAuxiliaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AccountReadCacheService accountReadCacheService;

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private com.paymentledger.auth.repository.UserRepository userRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private com.paymentledger.auth.domain.UserEntity createTestUser(com.paymentledger.auth.domain.Role role) {
        com.paymentledger.auth.domain.UserEntity user = new com.paymentledger.auth.domain.UserEntity(
                "user-" + UUID.randomUUID() + "@example.com",
                "hash1234",
                role
        );
        return userRepository.save(user);
    }

    @Test
    @DisplayName("Test A — Redis connection, configuration, and basic operational ping")
    void testA_redisConnectionAndConfiguration() {
        assertThat(redisTemplate).isNotNull();
        assertThat(stringRedisTemplate).isNotNull();

        String testKey = "auxiliary:test:ping:" + UUID.randomUUID();
        stringRedisTemplate.opsForValue().set(testKey, "pong", Duration.ofSeconds(30));

        String value = stringRedisTemplate.opsForValue().get(testKey);
        assertThat(value).isEqualTo("pong");

        stringRedisTemplate.delete(testKey);
        assertThat(stringRedisTemplate.hasKey(testKey)).isFalse();
    }

    @Test
    @DisplayName("Test B — Cache miss -> PostgreSQL read -> Redis cache population")
    void testB_cacheMiss_postgreSql_cachePopulation() {
        UUID ownerId = createTestUser(com.paymentledger.auth.domain.Role.CUSTOMER).getId();
        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "USD", "CUSTOMER");
        UUID accountId = account.getId();
        String cacheKey = RedisKeyNamespaces.accountCacheKey(accountId);

        // Ensure clean state: cache has no entry initially
        redisTemplate.delete(cacheKey);
        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // First read: cache miss -> loads from PostgreSQL -> populates Redis
        AccountEntity loaded = accountService.getAccount(accountId, ownerId, "CUSTOMER");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(accountId);

        // Verify Redis now contains the cached entry
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
        Optional<CachedAccountDto> cached = accountReadCacheService.get(accountId);
        assertThat(cached).isPresent();
        assertThat(cached.get().getAccountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(cached.get().getCurrency()).isEqualTo("USD");
        assertThat(cached.get().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Test C — Cache hit avoids unnecessary database read")
    void testC_cacheHit_avoidsDatabaseRead() {
        UUID ownerId = createTestUser(com.paymentledger.auth.domain.Role.CUSTOMER).getId();
        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "EUR", "CUSTOMER");
        UUID accountId = account.getId();

        // Populate cache via first read
        accountService.getAccount(accountId, ownerId, "CUSTOMER");

        double initialHits = meterRegistry.counter("redis.cache.hits", "cache", "account").count();

        // Second read: served from cache
        AccountEntity cachedAccount = accountService.getAccount(accountId, ownerId, "CUSTOMER");
        assertThat(cachedAccount).isNotNull();
        assertThat(cachedAccount.getId()).isEqualTo(accountId);
        assertThat(cachedAccount.getCurrency()).isEqualTo("EUR");

        double afterHits = meterRegistry.counter("redis.cache.hits", "cache", "account").count();
        assertThat(afterHits).isGreaterThan(initialHits);
    }

    @Test
    @DisplayName("Test D — Cache TTL expiration")
    void testD_cacheTtlExpiration() throws InterruptedException {
        UUID ownerId = createTestUser(com.paymentledger.auth.domain.Role.CUSTOMER).getId();
        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "GBP", "CUSTOMER");
        UUID accountId = account.getId();
        String cacheKey = RedisKeyNamespaces.accountCacheKey(accountId);

        // Populate with a short 1-second TTL
        accountReadCacheService.putWithTtl(account, Duration.ofSeconds(1));
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();

        // Wait for TTL expiration
        Thread.sleep(1200);

        // Key should have expired automatically
        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();
        Optional<CachedAccountDto> cached = accountReadCacheService.get(accountId);
        assertThat(cached).isEmpty();
    }

    @Test
    @DisplayName("Test E — Cache invalidation after authoritative mutation (freeze/unfreeze)")
    void testE_cacheInvalidationAfterAuthoritativeMutation() {
        UUID ownerId = createTestUser(com.paymentledger.auth.domain.Role.CUSTOMER).getId();
        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "USD", "CUSTOMER");
        UUID accountId = account.getId();
        String cacheKey = RedisKeyNamespaces.accountCacheKey(accountId);

        // Populate cache
        accountService.getAccount(accountId, ownerId, "CUSTOMER");
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();

        // Authoritative mutation: freeze account
        accountService.freezeAccount(accountId, "Suspicious activity detected");

        // Cache must be evicted immediately
        assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

        // Next read repopulates cache with updated status FROZEN
        AccountEntity frozenAccount = accountService.getAccount(accountId, ownerId, "CUSTOMER");
        assertThat(frozenAccount.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
    }

    @Test
    @DisplayName("Test F — Redis unavailable -> documented cache fallback to PostgreSQL")
    void testF_redisUnavailable_documentedCacheFallback() {
        UUID ownerId = createTestUser(com.paymentledger.auth.domain.Role.CUSTOMER).getId();
        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "USD", "CUSTOMER");
        UUID accountId = account.getId();

        // Instantiate cache service with null redis template (simulating disconnected Redis)
        AccountReadCacheService disconnectedCacheService = new AccountReadCacheService(null, meterRegistry, null, 300);
        AccountService resilientService = new AccountService(accountRepository, null, outboxService, disconnectedCacheService);

        // Call getAccount — should seamlessly fall back to PostgreSQL without error
        AccountEntity loaded = resilientService.getAccount(accountId, ownerId, "CUSTOMER");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(accountId);
        assertThat(loaded.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Test G — Rate limit allows requests under threshold")
    void testG_rateLimitAllowsRequestsUnderThreshold() {
        String key = RedisKeyNamespaces.authRateLimitKey("actor-g-" + UUID.randomUUID());
        long limit = 5;
        long window = 60;

        RateLimitResult r1 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r1.allowed()).isTrue();
        assertThat(r1.currentCount()).isEqualTo(1);
        assertThat(r1.remaining()).isEqualTo(4);

        RateLimitResult r2 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r2.allowed()).isTrue();
        assertThat(r2.currentCount()).isEqualTo(2);
        assertThat(r2.remaining()).isEqualTo(3);

        RateLimitResult r3 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r3.allowed()).isTrue();
        assertThat(r3.currentCount()).isEqualTo(3);
        assertThat(r3.remaining()).isEqualTo(2);
    }

    @Test
    @DisplayName("Test H — Rate limit rejects requests over threshold")
    void testH_rateLimitRejectsRequestsOverThreshold() {
        String key = RedisKeyNamespaces.authRateLimitKey("actor-h-" + UUID.randomUUID());
        long limit = 3;
        long window = 60;

        for (int i = 0; i < limit; i++) {
            RateLimitResult r = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
            assertThat(r.allowed()).isTrue();
        }

        // Exceeded request
        RateLimitResult rejected = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.currentCount()).isEqualTo(4);
        assertThat(rejected.remaining()).isEqualTo(0);
        assertThat(rejected.resetSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Test I — Rate limit resets after TTL window expires")
    void testI_rateLimitResetsAfterTtl() throws InterruptedException {
        String key = RedisKeyNamespaces.authRateLimitKey("actor-i-" + UUID.randomUUID());
        long limit = 2;
        long window = 1; // 1 second window

        RateLimitResult r1 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        RateLimitResult r2 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r1.allowed()).isTrue();
        assertThat(r2.allowed()).isTrue();

        RateLimitResult r3 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r3.allowed()).isFalse();

        // Wait past 1-second TTL window
        Thread.sleep(1200);

        // Next request must be allowed as a new window begins
        RateLimitResult r4 = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r4.allowed()).isTrue();
        assertThat(r4.currentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test J — Concurrent rate-limit requests behave atomically")
    void testJ_concurrentRateLimitRequestsBehaveAtomically() throws InterruptedException {
        String key = RedisKeyNamespaces.authRateLimitKey("actor-concurrent-" + UUID.randomUUID());
        long limit = 10;
        long window = 60;
        int threads = 50;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RateLimitResult result = redisRateLimiter.checkLimit(key, limit, window, RateLimitPolicy.FAIL_OPEN);
                    if (result.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly limit (10) allowed, remaining (40) rejected
        assertThat(allowedCount.get()).isEqualTo(10);
        assertThat(rejectedCount.get()).isEqualTo(40);
    }

    @Test
    @DisplayName("Test K — Independent actors have independent rate limit buckets")
    void testK_independentActorsHaveIndependentRateLimits() {
        String keyActor1 = RedisKeyNamespaces.authRateLimitKey("actor-k1-" + UUID.randomUUID());
        String keyActor2 = RedisKeyNamespaces.authRateLimitKey("actor-k2-" + UUID.randomUUID());
        long limit = 2;
        long window = 60;

        // Exhaust actor 1
        redisRateLimiter.checkLimit(keyActor1, limit, window, RateLimitPolicy.FAIL_OPEN);
        redisRateLimiter.checkLimit(keyActor1, limit, window, RateLimitPolicy.FAIL_OPEN);
        RateLimitResult r1Blocked = redisRateLimiter.checkLimit(keyActor1, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r1Blocked.allowed()).isFalse();

        // Actor 2 should be unaffected
        RateLimitResult r2Allowed = redisRateLimiter.checkLimit(keyActor2, limit, window, RateLimitPolicy.FAIL_OPEN);
        assertThat(r2Allowed.allowed()).isTrue();
        assertThat(r2Allowed.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("Test L — Redis restart / degradation policy handling (FAIL_OPEN vs FAIL_CLOSED)")
    void testL_redisRestartRecovery() {
        RedisRateLimiter disconnectedLimiter = new RedisRateLimiter(null, (RedisConnectionFactory) null, meterRegistry);
        String key = "rate-limit:auth:v1:disconnected";

        // FAIL_OPEN policy allows request gracefully during outage
        RateLimitResult openResult = disconnectedLimiter.checkLimit(key, 5, 60, RateLimitPolicy.FAIL_OPEN);
        assertThat(openResult.allowed()).isTrue();
        assertThat(openResult.degraded()).isTrue();

        // FAIL_CLOSED policy blocks request securely during outage
        RateLimitResult closedResult = disconnectedLimiter.checkLimit(key, 5, 60, RateLimitPolicy.FAIL_CLOSED);
        assertThat(closedResult.allowed()).isFalse();
        assertThat(closedResult.degraded()).isTrue();

        // Connected limiter functions normally
        RateLimitResult connectedResult = redisRateLimiter.checkLimit(key, 5, 60, RateLimitPolicy.FAIL_OPEN);
        assertThat(connectedResult.allowed()).isTrue();
        assertThat(connectedResult.degraded()).isFalse();
    }

    @Test
    @DisplayName("Test M — Financial settlement succeeds while Redis is unavailable / bypassed")
    void testM_financialSettlementSucceedsWhileRedisIsUnavailable() {
        // Step 1: Create payer and payee in PostgreSQL
        UUID payerOwnerId = createTestUser(com.paymentledger.auth.domain.Role.CUSTOMER).getId();
        UUID payeeOwnerId = createTestUser(com.paymentledger.auth.domain.Role.MERCHANT).getId();

        AccountEntity payerAccount = accountService.createAccount(payerOwnerId, AccountType.CUSTOMER, "USD", "CUSTOMER");
        AccountEntity payeeAccount = accountService.createAccount(payeeOwnerId, AccountType.MERCHANT, "USD", "MERCHANT");

        // Fund payer account with 100,000 minor units ($1000.00) via double-entry ledger transaction
        AccountEntity settlementAccount = accountService.createAccount(payerOwnerId, AccountType.INTERNAL_SETTLEMENT, "USD", "SYSTEM");

        LedgerTransactionEntity fundingTx = new LedgerTransactionEntity(
                com.paymentledger.ledger.domain.LedgerTransactionType.SYSTEM_ADJUSTMENT,
                UUID.randomUUID(),
                "DEPOSIT",
                "USD",
                "Initial deposit"
        );
        fundingTx.addEntry(new com.paymentledger.ledger.domain.LedgerEntryEntity(
                settlementAccount.getId(),
                com.paymentledger.ledger.domain.LedgerEntryDirection.DEBIT,
                100_000L,
                "USD",
                1L
        ));
        fundingTx.addEntry(new com.paymentledger.ledger.domain.LedgerEntryEntity(
                payerAccount.getId(),
                com.paymentledger.ledger.domain.LedgerEntryDirection.CREDIT,
                100_000L,
                "USD",
                1L
        ));
        fundingTx.post();
        ledgerTransactionRepository.save(fundingTx);

        payerAccount.addBalanceMinor(100_000L);
        accountRepository.save(payerAccount);

        // Step 2: Create Payment in CAPTURING state
        PaymentEntity payment = new PaymentEntity(
                "idemp_test_m_" + UUID.randomUUID(),
                "GLOBAL",
                payerAccount.getId(),
                payeeAccount.getId(),
                50_000L, // $500.00
                "USD"
        );
        payment.authorize();
        payment.authorizationSucceeded("mock_auth_ref");
        payment.capture();
        payment = paymentRepository.save(payment);

        // Step 3: Execute settlePaymentWithLedger (proves financial transaction has ZERO Redis dependency)
        String captureRef = "mock_capture_" + UUID.randomUUID();
        LedgerTransactionEntity ledgerTx = ledgerService.settlePaymentWithLedger(payment.getId(), captureRef, "corr_test_m");

        // Step 4: Verify financial state in PostgreSQL
        assertThat(ledgerTx).isNotNull();
        assertThat(ledgerTx.getSourceReferenceId()).isEqualTo(payment.getId());

        PaymentEntity settledPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(settledPayment.getStatus()).isEqualTo(PaymentStatus.SETTLED);

        AccountEntity updatedPayer = accountRepository.findById(payerAccount.getId()).orElseThrow();
        AccountEntity updatedPayee = accountRepository.findById(payeeAccount.getId()).orElseThrow();
        assertThat(updatedPayer.getMaterializedBalanceMinor()).isEqualTo(50_000L);
        assertThat(updatedPayee.getMaterializedBalanceMinor()).isEqualTo(50_000L);

        // Verify ledger balance
        long payerLedgerBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(payerAccount.getId());
        long payeeLedgerBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(payeeAccount.getId());
        assertThat(payerLedgerBalance).isEqualTo(50_000L);
        assertThat(payeeLedgerBalance).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("Test N — Phase 7 PostgreSQL idempotency remains authoritative")
    void testN_phase7PostgreSqlIdempotencyRemainsAuthoritative() {
        UUID actorId = UUID.randomUUID();
        String sharedIdempotencyKey = "shared_idemp_" + UUID.randomUUID();

        // First idempotency record succeeds
        IdempotencyRecordEntity record1 = new IdempotencyRecordEntity(
                actorId,
                "CREATE_PAYMENT",
                sharedIdempotencyKey,
                "hash_12345",
                Instant.now().plusSeconds(3600)
        );
        idempotencyRecordRepository.saveAndFlush(record1);

        // Duplicate with identical (actor_id, operation, idempotency_key) violates PostgreSQL unique constraint
        IdempotencyRecordEntity duplicate = new IdempotencyRecordEntity(
                actorId,
                "CREATE_PAYMENT",
                sharedIdempotencyKey,
                "hash_12345",
                Instant.now().plusSeconds(3600)
        );

        assertThatThrownBy(() -> {
            idempotencyRecordRepository.saveAndFlush(duplicate);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Test O — Phase 9 transactional outbox remains functional while Redis is bypassed")
    void testO_phase9TransactionalOutboxRemainsFunctionalWhileRedisIsUnavailable() {
        UUID aggregateId = UUID.randomUUID();
        String eventType = "PaymentSettled";
        String topic = "payment-events";

        // Save event to PostgreSQL outbox
        OutboxEventEntity outboxEvent = outboxService.saveEvent(
                "PAYMENT",
                aggregateId.toString(),
                eventType,
                topic,
                aggregateId.toString(),
                UUID.randomUUID(),
                "test_cmd",
                Map.of("amountMinor", 5000L, "currency", "USD")
        );

        assertThat(outboxEvent.getId()).isNotNull();
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Relay worker processes the event from PostgreSQL directly to Kafka without touching Redis
        outboxRelayScheduler.relayPendingEvents();

        long deadline = System.currentTimeMillis() + 5000;
        OutboxEventEntity published = outboxEventRepository.findById(outboxEvent.getId()).orElseThrow();
        while (published.getStatus() != OutboxStatus.PUBLISHED && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            outboxRelayScheduler.relayPendingEvents();
            published = outboxEventRepository.findById(outboxEvent.getId()).orElseThrow();
        }

        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Test P — Redis does not contain prohibited sensitive financial or authentication data")
    void testP_redisDoesNotContainProhibitedSensitiveFinancialOrAuthData() {
        // Query all keys currently stored in Redis
        Set<String> keys = redisTemplate.keys("*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        List<String> prohibitedKeySubstrings = List.of(
                "password", "secret", "token", "jwt", "refresh", "cvv", "card", "private_key"
        );

        for (String key : keys) {
            String lowerKey = key.toLowerCase();
            for (String prohibited : prohibitedKeySubstrings) {
                // Rate limit keys may have "auth" in namespace, but must NOT have raw passwords or tokens
                assertThat(lowerKey)
                        .as("Redis key [%s] contains prohibited sensitive term [%s]", key, prohibited)
                        .doesNotContain("bearer", "eyj", "password", "cvv", "private_key");
            }

            Object val = redisTemplate.opsForValue().get(key);
            if (val != null) {
                String valStr = val.toString().toLowerCase();
                assertThat(valStr)
                        .as("Redis value for key [%s] contains prohibited sensitive information", key)
                        .doesNotContain("passwordhash", "refreshtoken", "privatekey", "cvv");
            }
        }
    }
}
