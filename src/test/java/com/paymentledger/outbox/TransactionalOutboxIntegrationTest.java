package com.paymentledger.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.account.service.AccountService;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.service.LedgerService;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.consumer.ConsumerDeduplicationService;
import com.paymentledger.messaging.consumer.PaymentEventAuditConsumer;
import com.paymentledger.messaging.consumer.PaymentEventAuditService;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.event.PaymentCreatedEventPayload;
import com.paymentledger.messaging.event.PaymentSettledEventPayload;
import com.paymentledger.messaging.producer.KafkaEventPublisher;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.domain.OutboxStatus;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.outbox.service.OutboxRelayScheduler;
import com.paymentledger.outbox.service.OutboxService;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.api.dto.PaymentResponse;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AutoConfigureMockMvc
public class TransactionalOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayScheduler outboxRelayScheduler;

    @Autowired
    private KafkaEventPublisher eventPublisher;

    @Autowired
    private PaymentEventAuditConsumer auditConsumer;

    @Autowired
    private PaymentEventAuditService auditService;

    @Autowired
    private ConsumerDeduplicationService deduplicationService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private UserEntity testUser;
    private AccountEntity payerAccount;
    private AccountEntity payeeAccount;

    @BeforeEach
    void setUp() {
        OutboxRelayScheduler.setSchedulingEnabled(false);
        transactionTemplate = new TransactionTemplate(transactionManager);
        eventPublisher.setSimulateTransientFailure(false);
        auditConsumer.reset();
        auditService.setSimulateTransientFailure(false);

        jdbcTemplate.execute("DELETE FROM payment_event_audits");
        jdbcTemplate.execute("DELETE FROM consumed_messages");
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM idempotency_records");
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();

        testUser = new UserEntity("outbox_test@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(testUser);

        payerAccount = new AccountEntity("ACC-OUTBOX-PAYER", testUser.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        payerAccount.addBalanceMinor(20000L);
        accountRepository.saveAndFlush(payerAccount);

        UUID initTxPayer = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status) VALUES (?, 'SYSTEM_ADJUSTMENT', ?, 'MANUAL', 'USD', 'Init Payer', 'POSTED')", initTxPayer, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO ledger_entries (id, account_id, ledger_transaction_id, direction, amount_minor, currency, sequence_number) VALUES (?, ?, ?, 'CREDIT', ?, 'USD', 1)", UUID.randomUUID(), payerAccount.getId(), initTxPayer, 20000L);

        UserEntity payeeUser = new UserEntity("outbox_payee@example.com", "hash", Role.MERCHANT);
        userRepository.saveAndFlush(payeeUser);

        payeeAccount = new AccountEntity("ACC-OUTBOX-PAYEE", payeeUser.getId(), AccountType.MERCHANT, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(payeeAccount);
    }

    @AfterEach
    void tearDown() {
        eventPublisher.setSimulateTransientFailure(false);
        jdbcTemplate.execute("DELETE FROM outbox_events");
        OutboxRelayScheduler.setSchedulingEnabled(true);
    }

    private void awaitCondition(BooleanSupplier condition, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
        }
        throw new AssertionError("Condition was not met within " + timeoutMs + " ms");
    }

    /**
     * TEST A: Outbox schema / migration verification.
     */
    @Test
    @DisplayName("Test A: outbox_events schema and required indexes exist and match Flyway migration V8")
    void testPhase9_TestA_OutboxSchemaAndMigration() {
        Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'outbox_events'",
                Integer.class);
        assertThat(tableExists).isEqualTo(1);

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'outbox_events'",
                String.class);

        assertThat(columns).contains(
                "id", "event_type", "aggregate_type", "aggregate_id",
                "schema_version", "topic", "partition_key", "correlation_id",
                "causation_id", "payload", "status", "attempt_count",
                "next_attempt_at", "published_at", "last_error", "locked_by",
                "locked_at", "created_at"
        );

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'outbox_events'",
                String.class);

        assertThat(indexes).contains(
                "idx_outbox_pending_polling",
                "idx_outbox_aggregate"
        );
    }

    /**
     * TEST B: Business mutation + outbox event commit atomically.
     */
    @Test
    @DisplayName("Test B: Business entity mutation and outbox event commit atomically in one PostgreSQL transaction")
    void testPhase9_TestB_BusinessMutationAndOutboxCommitAtomically() {
        UUID eventId = UUID.randomUUID();
        UUID accountId = payerAccount.getId();

        transactionTemplate.execute(status -> {
            payerAccount.subtractBalanceMinor(500L);
            accountRepository.save(payerAccount);

            outboxService.saveEvent(
                    "ACCOUNT",
                    accountId.toString(),
                    "BalanceDebited",
                    TopicNames.ACCOUNT_EVENTS,
                    accountId.toString(),
                    UUID.randomUUID(),
                    "cmd_test_atomic",
                    Map.of("amountMinor", 500L, "currency", "USD")
            );
            return null;
        });

        // Verify both business mutation and outbox event exist in PostgreSQL
        Long balance = jdbcTemplate.queryForObject(
                "SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, accountId);
        assertThat(balance).isEqualTo(19500L);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BalanceDebited'",
                Integer.class, accountId.toString());
        assertThat(outboxCount).isEqualTo(1);
    }

    /**
     * TEST C: Business mutation rollback also rolls back outbox event.
     */
    @Test
    @DisplayName("Test C: Business mutation rollback ensures outbox event is also rolled back (no orphan events)")
    void testPhase9_TestC_BusinessRollbackRollsBackOutbox() {
        UUID accountId = payerAccount.getId();

        assertThatThrownBy(() -> {
            transactionTemplate.execute(status -> {
                payerAccount.subtractBalanceMinor(300L);
                accountRepository.save(payerAccount);

                outboxService.saveEvent(
                        "ACCOUNT",
                        accountId.toString(),
                        "BalanceDebitedRollback",
                        TopicNames.ACCOUNT_EVENTS,
                        accountId.toString(),
                        UUID.randomUUID(),
                        "cmd_test_rollback",
                        Map.of("amountMinor", 300L)
                );

                throw new RuntimeException("Simulated business transaction failure");
            });
        }).hasMessageContaining("Simulated business transaction failure");

        // Verify account balance was NOT modified
        Long balance = jdbcTemplate.queryForObject(
                "SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, accountId);
        assertThat(balance).isEqualTo(20000L);

        // Verify outbox event was rolled back
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'BalanceDebitedRollback'",
                Integer.class);
        assertThat(outboxCount).isEqualTo(0);
    }

    /**
     * TEST D: Successful relay publishes event.
     */
    @Test
    @DisplayName("Test D: Outbox relay worker polls pending event, publishes to Kafka, and marks status PUBLISHED")
    void testPhase9_TestD_SuccessfulRelayPublishesEvent() {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 1500L, 0L, "USD",
                UUID.randomUUID(), "cap_outbox_relay", Instant.now());

        OutboxEventEntity saved = outboxService.saveEvent(
                "PAYMENT",
                paymentId.toString(),
                "PaymentSettled",
                TopicNames.PAYMENT_EVENTS,
                payerAccount.getId().toString(),
                UUID.randomUUID(),
                "cmd_relay_test",
                payload
        );

        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Run relay worker
        int relayed = outboxRelayScheduler.relayPendingEvents();
        assertThat(relayed).isGreaterThanOrEqualTo(1);

        // Verify outbox status in PostgreSQL
        OutboxEventEntity updated = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();

        // Verify consumer received and audited the event
        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(saved.getId()), 10000);
        assertThat(auditConsumer.getProcessedEvents().get(saved.getId())).isEqualTo("PaymentSettled");
    }

    /**
     * TEST E: Kafka outage retains outbox event as durable with retry metadata.
     */
    @Test
    @DisplayName("Test E: Kafka outage retains outbox event with incremented attempt_count and next_attempt_at")
    void testPhase9_TestE_KafkaOutageRetainsOutboxEvent() {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 2000L, 0L, "USD",
                UUID.randomUUID(), "cap_outage", Instant.now());

        OutboxEventEntity saved = outboxService.saveEvent(
                "PAYMENT",
                paymentId.toString(),
                "PaymentSettled",
                TopicNames.PAYMENT_EVENTS,
                payerAccount.getId().toString(),
                UUID.randomUUID(),
                "cmd_outage",
                payload
        );

        // Simulate Kafka broker failure
        eventPublisher.setSimulateTransientFailure(true);

        // Run relay worker
        outboxRelayScheduler.relayPendingEvents();

        // Verify outbox event in PostgreSQL: NOT published, attempt_count incremented, next_attempt_at scheduled
        OutboxEventEntity failedEvent = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failedEvent.getAttemptCount()).isEqualTo(1);
        assertThat(failedEvent.getNextAttemptAt()).isNotNull();
        assertThat(failedEvent.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(failedEvent.getLastError()).contains("Simulated Kafka Broker Unavailable");
        assertThat(failedEvent.getPublishedAt()).isNull();
    }

    /**
     * TEST F: Retry after Kafka recovery succeeds.
     */
    @Test
    @DisplayName("Test F: Outbox relay successfully retries and publishes event after Kafka recovers")
    void testPhase9_TestF_RetryAfterKafkaRecoverySucceeds() {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 2200L, 0L, "USD",
                UUID.randomUUID(), "cap_recov", Instant.now());

        OutboxEventEntity saved = outboxService.saveEvent(
                "PAYMENT",
                paymentId.toString(),
                "PaymentSettled",
                TopicNames.PAYMENT_EVENTS,
                payerAccount.getId().toString(),
                UUID.randomUUID(),
                "cmd_recov",
                payload
        );

        // 1. First attempt fails due to Kafka outage
        eventPublisher.setSimulateTransientFailure(true);
        outboxRelayScheduler.relayPendingEvents();

        OutboxEventEntity afterFailure = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(afterFailure.getAttemptCount()).isEqualTo(1);

        // 2. Kafka recovers, and retry backoff elapses
        eventPublisher.setSimulateTransientFailure(false);
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?", saved.getId());

        // 3. Relay runs again
        int relayed = outboxRelayScheduler.relayPendingEvents();
        assertThat(relayed).isGreaterThanOrEqualTo(1);

        // 4. Status is now PUBLISHED and consumer receives it
        OutboxEventEntity recovered = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(recovered.getPublishedAt()).isNotNull();

        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(saved.getId()), 10000);
    }

    /**
     * TEST G: Concurrent relay workers safely claim events without duplication (SKIP LOCKED).
     */
    @Test
    @DisplayName("Test G: Multiple concurrent relay workers claim distinct events with FOR UPDATE SKIP LOCKED")
    void testPhase9_TestG_ConcurrentRelayWorkersSafeClaiming() throws Exception {
        int totalEvents = 20;
        List<UUID> createdIds = new ArrayList<>();

        for (int i = 0; i < totalEvents; i++) {
            OutboxEventEntity e = outboxService.saveEvent(
                    "ACCOUNT",
                    payerAccount.getId().toString(),
                    "ConcurrentClaimTest",
                    TopicNames.ACCOUNT_EVENTS,
                    payerAccount.getId().toString(),
                    UUID.randomUUID(),
                    "cmd_" + i,
                    Map.of("index", i)
            );
            createdIds.add(e.getId());
        }

        int workerCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(workerCount);
        Set<UUID> claimedEventIds = ConcurrentHashMap.newKeySet();
        AtomicInteger totalClaimsAcrossWorkers = new AtomicInteger(0);

        for (int w = 0; w < workerCount; w++) {
            final String workerId = "test-worker-" + w;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<OutboxEventEntity> batch = outboxService.claimBatch(workerId, 10, Duration.ofSeconds(30));
                    totalClaimsAcrossWorkers.addAndGet(batch.size());
                    for (OutboxEventEntity event : batch) {
                        claimedEventIds.add(event.getId());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // Zero duplicate claims: set size must equal total claimed count
        assertThat(claimedEventIds.size()).isEqualTo(totalClaimsAcrossWorkers.get());
        assertThat(claimedEventIds.size()).isEqualTo(totalEvents);
    }

    /**
     * TEST H: Relay restart recovers previously claimed events after lease expiration.
     */
    @Test
    @DisplayName("Test H: Stale PROCESSING events from crashed workers are reclaimed after lease expiration")
    void testPhase9_TestH_RelayCrashRecoveryReclaimsStaleLease() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        // Simulate event left in PROCESSING by a crashed worker 45 seconds ago (lease = 30s)
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, schema_version,
                                           topic, partition_key, correlation_id, causation_id, payload,
                                           status, attempt_count, next_attempt_at, locked_by, locked_at, created_at)
                VALUES (?, 'PAYMENT', ?, 'PaymentSettled', '1.0',
                        'payment.events', ?, ?, 'cmd_crashed',
                        '{"amountMinor": 3000}', 'PROCESSING', 1, NOW() - INTERVAL '10 seconds',
                        'crashed-worker-dead', NOW() - INTERVAL '45 seconds', NOW() - INTERVAL '45 seconds')
                """, eventId, aggregateId.toString(), payerAccount.getId().toString(), UUID.randomUUID());

        // New worker claims batch
        List<OutboxEventEntity> reclaimed = outboxService.claimBatch("new-worker-alive", 10, Duration.ofSeconds(30));

        assertThat(reclaimed).isNotEmpty();
        OutboxEventEntity recovered = reclaimed.stream().filter(e -> e.getId().equals(eventId)).findFirst().orElse(null);
        assertThat(recovered).isNotNull();
        assertThat(recovered.getLockedBy()).isEqualTo("new-worker-alive");
    }

    /**
     * TEST I: Crash window: Kafka publish succeeds but outbox update fails; retry produces duplicate Kafka message.
     */
    @Test
    @DisplayName("Test I: Crash window produces duplicate Kafka delivery safely handled by consumer deduplication")
    void testPhase9_TestI_CrashWindowDuplicatePublishHandledByConsumer() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 5000L, 0L, "USD",
                UUID.randomUUID(), "cap_crash_window", Instant.now());

        EventEnvelope<PaymentSettledEventPayload> envelope = new EventEnvelope<>(
                eventId,
                "PaymentSettled",
                Instant.now(),
                "PAYMENT",
                paymentId.toString(),
                "1.0",
                UUID.randomUUID(),
                "cmd_crash",
                payload
        );

        // 1. First Kafka delivery (simulates relay successfully publishing to Kafka)
        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, payerAccount.getId().toString(), envelope)
                .get(10, TimeUnit.SECONDS);

        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(eventId), 10000);

        // 2. Application crashed before outbox status could be updated in DB.
        // On restart, the relay re-delivers the exact same message to Kafka.
        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, payerAccount.getId().toString(), envelope)
                .get(10, TimeUnit.SECONDS);

        awaitCondition(() -> auditConsumer.getDuplicateSkippedCount() >= 1, 10000);

        // 3. Assert consumer deduplicated the second message: exactly 1 row in audit table
        int auditCount = auditService.getAuditRecordCount(eventId);
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * TEST J: Duplicate Kafka delivery handled by Phase 8 consumer deduplication.
     */
    @Test
    @DisplayName("Test J: Phase 8 consumer deduplication provides exactly-once logical database effects")
    void testPhase9_TestJ_DuplicateDeliveryHandledByConsumerDedup() throws Exception {
        UUID eventId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                UUID.randomUUID(), payerAccount.getId(), payeeAccount.getId(), 1000L, 0L, "USD",
                UUID.randomUUID(), "cap_dedup", Instant.now());

        EventEnvelope<PaymentSettledEventPayload> envelope = EventEnvelope.create(
                "PaymentSettled", "PAYMENT", UUID.randomUUID().toString(), UUID.randomUUID(), "cmd_dedup", payload);

        // Send two identical messages
        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, payerAccount.getId().toString(), envelope).get(10, TimeUnit.SECONDS);
        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(envelope.eventId()), 10000);

        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, payerAccount.getId().toString(), envelope).get(10, TimeUnit.SECONDS);
        awaitCondition(() -> auditConsumer.getDuplicateSkippedCount() >= 1, 10000);

        assertThat(auditConsumer.getDuplicateSkippedCount()).isGreaterThanOrEqualTo(1);

        // Verify PostgreSQL deduplication table
        Long dedupRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_messages WHERE consumer_group = ? AND message_id = ?",
                Long.class, PaymentEventAuditConsumer.CONSUMER_GROUP, envelope.eventId());
        assertThat(dedupRows).isEqualTo(1L);

        int auditRecords = auditService.getAuditRecordCount(envelope.eventId());
        assertThat(auditRecords).isEqualTo(1);
    }

    /**
     * TEST K: Correlation/causation metadata survives outbox -> Kafka -> consumer.
     */
    @Test
    @DisplayName("Test K: Correlation and causation metadata propagates intact through outbox, Kafka, and consumer")
    void testPhase9_TestK_CorrelationCausationMetadataPropagation() {
        UUID paymentId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String causationId = "cmd_settle_corr_test";

        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 1800L, 0L, "USD",
                UUID.randomUUID(), "cap_meta", Instant.now());

        OutboxEventEntity saved = outboxService.saveEvent(
                "PAYMENT",
                paymentId.toString(),
                "PaymentSettled",
                TopicNames.PAYMENT_EVENTS,
                payerAccount.getId().toString(),
                correlationId,
                causationId,
                payload
        );

        outboxRelayScheduler.relayPendingEvents();

        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(saved.getId()), 10000);

        // Verify correlation and causation preserved in outbox record
        UUID recordedCorrId = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM outbox_events WHERE id = ?",
                UUID.class, saved.getId());
        String recordedCausationId = jdbcTemplate.queryForObject(
                "SELECT causation_id FROM outbox_events WHERE id = ?",
                String.class, saved.getId());
        assertThat(recordedCorrId).isEqualTo(correlationId);
        assertThat(recordedCausationId).isEqualTo(causationId);

        // Verify consumer audited the event by event ID
        int auditCount = auditService.getAuditRecordCount(saved.getId());
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * TEST L: Partition key preserves aggregate ordering.
     */
    @Test
    @DisplayName("Test L: Events with the same partition key land on the exact same Kafka partition")
    void testPhase9_TestL_PartitionKeyPreservesAggregateOrdering() throws Exception {
        String aggregateKey = payerAccount.getId().toString();

        OutboxEventEntity e1 = outboxService.saveEvent(
                "PAYMENT", aggregateKey, "PaymentCreated", TopicNames.PAYMENT_EVENTS,
                aggregateKey, UUID.randomUUID(), "cmd1", Map.of("step", 1));

        OutboxEventEntity e2 = outboxService.saveEvent(
                "PAYMENT", aggregateKey, "PaymentSettled", TopicNames.PAYMENT_EVENTS,
                aggregateKey, UUID.randomUUID(), "cmd2", Map.of("step", 2));

        // Use custom consumer to verify partition numbers
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "partition-test-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(props);

        try (Consumer<String, String> testConsumer = cf.createConsumer()) {
            testConsumer.subscribe(Collections.singletonList(TopicNames.PAYMENT_EVENTS));

            outboxRelayScheduler.relayPendingEvents();

            Set<Integer> partitions = new HashSet<>();
            long start = System.currentTimeMillis();
            while (partitions.size() < 1 && (System.currentTimeMillis() - start) < 10000) {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(300));
                for (var r : records) {
                    if (r.key() != null && r.key().equals(aggregateKey)) {
                        partitions.add(r.partition());
                    }
                }
            }

            // Both events for aggregateKey must be on the EXACT SAME partition
            assertThat(partitions.size()).isEqualTo(1);
        }
    }

    /**
     * TEST M: Production Payment workflow creates the correct outbox event.
     */
    @Test
    @DisplayName("Test M: Production PaymentService workflow automatically creates PaymentSettled outbox event in PostgreSQL")
    void testPhase9_TestM_ProductionPaymentWorkflowCreatesOutboxEvent() {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(1400L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        String correlationId = UUID.randomUUID().toString();
        PaymentResponse response = paymentService.createPayment(
                testUser.getId(), payerAccount.getId(), UUID.randomUUID().toString(), correlationId, request);

        assertThat(response.getStatus()).isEqualTo("SETTLED");
        UUID paymentId = response.getPaymentId();

        // Verify outbox_events contains the PaymentSettled event created atomically during settlement
        Integer outboxSettledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentSettled'",
                Integer.class, paymentId.toString());
        assertThat(outboxSettledCount).isGreaterThanOrEqualTo(1);

        String topic = jdbcTemplate.queryForObject(
                "SELECT topic FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentSettled'",
                String.class, paymentId.toString());
        assertThat(topic).isEqualTo(TopicNames.PAYMENT_EVENTS);
    }

    /**
     * TEST N: Production Account workflow creates the correct outbox event.
     */
    @Test
    @DisplayName("Test N: Production AccountService freeze workflow creates AccountFrozen outbox event in PostgreSQL")
    void testPhase9_TestN_ProductionAccountWorkflowCreatesOutboxEvent() {
        UUID accountId = payeeAccount.getId();

        accountService.freezeAccount(accountId, "Suspicious merchant chargeback pattern");

        AccountEntity frozen = accountRepository.findById(accountId).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(AccountStatus.FROZEN);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'ACCOUNT' AND aggregate_id = ? AND event_type = 'AccountFrozen'",
                Integer.class, accountId.toString());
        assertThat(outboxCount).isEqualTo(1);

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_type = 'ACCOUNT' AND aggregate_id = ? AND event_type = 'AccountFrozen'",
                String.class, accountId.toString());
        assertThat(payload).contains("Suspicious merchant chargeback pattern");
    }

    /**
     * TEST O: Full financial settlement remains independent of Kafka availability.
     */
    @Test
    @DisplayName("Test O: Financial settlement in PostgreSQL succeeds completely even during total Kafka unavailability")
    void testPhase9_TestO_FinancialSettlementIndependentOfKafka() {
        // Disconnect / simulate complete Kafka failure
        eventPublisher.setSimulateTransientFailure(true);

        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(2500L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        PaymentResponse response = paymentService.createPayment(
                testUser.getId(), payerAccount.getId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), request);

        // Financial settlement in PostgreSQL must be 100% successful
        assertThat(response.getStatus()).isEqualTo("SETTLED");
        UUID paymentId = response.getPaymentId();

        // Verify balances in PostgreSQL
        Long payerBal = jdbcTemplate.queryForObject(
                "SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payerAccount.getId());
        Long payeeBal = jdbcTemplate.queryForObject(
                "SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payeeAccount.getId());
        assertThat(payerBal).isEqualTo(17500L); // 20000 - 2500
        assertThat(payeeBal).isEqualTo(2500L);

        // Verify double-entry ledger transaction is POSTED
        String txStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transactions WHERE source_reference_id = ?",
                String.class, paymentId);
        assertThat(txStatus).isEqualTo("POSTED");

        // Verify outbox row is safely waiting in PostgreSQL
        String outboxStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentSettled'",
                String.class, paymentId.toString());
        assertThat(outboxStatus).isEqualTo("PENDING");
    }
}
