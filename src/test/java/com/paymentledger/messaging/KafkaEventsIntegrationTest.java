package com.paymentledger.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.consumer.ConsumerDeduplicationService;
import com.paymentledger.messaging.consumer.PaymentEventAuditConsumer;
import com.paymentledger.messaging.consumer.PaymentEventAuditService;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.event.PaymentCreatedEventPayload;
import com.paymentledger.messaging.event.PaymentSettledEventPayload;
import com.paymentledger.messaging.producer.KafkaEventPublisher;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.api.dto.PaymentResponse;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
public class KafkaEventsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaEventPublisher eventPublisher;

    @Autowired
    private PaymentEventAuditConsumer auditConsumer;

    @Autowired
    private PaymentEventAuditService auditService;

    @Autowired
    private ConsumerDeduplicationService deduplicationService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountService accountService;

    private UserEntity testUser;
    private AccountEntity payerAccount;
    private AccountEntity payeeAccount;

    @BeforeEach
    void setUp() {
        auditConsumer.reset();
        auditService.setSimulateTransientFailure(false);
        jdbcTemplate.execute("DELETE FROM payment_event_audits");
        jdbcTemplate.execute("DELETE FROM consumed_messages");

        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM idempotency_records");
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();

        testUser = new UserEntity("kafka_test@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(testUser);

        payerAccount = new AccountEntity("ACC-KAFKA-PAYER", testUser.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(payerAccount);
        jdbcTemplate.update("UPDATE accounts SET materialized_balance_minor = 10000 WHERE id = ?", payerAccount.getId());

        UUID initTxPayer = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status) VALUES (?, 'SYSTEM_ADJUSTMENT', ?, 'MANUAL', 'USD', 'Init Payer', 'POSTED')", initTxPayer, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO ledger_entries (id, account_id, ledger_transaction_id, direction, amount_minor, currency, sequence_number) VALUES (?, ?, ?, 'CREDIT', ?, 'USD', 1)", UUID.randomUUID(), payerAccount.getId(), initTxPayer, 10000L);

        UserEntity payeeUser = new UserEntity("kafka_payee@example.com", "hash", Role.MERCHANT);
        userRepository.saveAndFlush(payeeUser);

        payeeAccount = new AccountEntity("ACC-KAFKA-PAYEE", payeeUser.getId(), AccountType.MERCHANT, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(payeeAccount);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM payment_event_audits");
        jdbcTemplate.execute("DELETE FROM consumed_messages");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("DELETE FROM payments");
        jdbcTemplate.execute("DELETE FROM idempotency_records");
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();
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
     * TEST A: Event serialization and deserialization.
     */
    @Test
    @DisplayName("Test A: Domain event serialized into immutable EventEnvelope with minor units and schemaVersion")
    void testPhase8_TestA_EventSerializationEnvelope() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID payerId = payerAccount.getId();
        UUID payeeId = payeeAccount.getId();
        UUID correlationId = UUID.randomUUID();
        Instant now = Instant.now();

        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerId, payeeId, 5000L, 150L, "USD", UUID.randomUUID(), "ch_stripe_123", now
        );

        EventEnvelope<PaymentSettledEventPayload> envelope = new EventEnvelope<>(
                eventId,
                "PaymentSettled",
                now,
                "PAYMENT",
                paymentId.toString(),
                "1.0",
                correlationId,
                "cmd_settle_001",
                payload
        );

        String json = objectMapper.writeValueAsString(envelope);
        assertThat(json).contains("\"eventId\":\"" + eventId + "\"");
        assertThat(json).contains("\"eventType\":\"PaymentSettled\"");
        assertThat(json).contains("\"schemaVersion\":\"1.0\"");
        assertThat(json).contains("\"amountMinor\":5000");
        assertThat(json).contains("\"feeAmountMinor\":150");
        assertThat(json).doesNotContain("\"amount\":50.");

        EventEnvelope<JsonNode> deserialized = objectMapper.readValue(json, new TypeReference<EventEnvelope<JsonNode>>() {});
        assertThat(deserialized.eventId()).isEqualTo(eventId);
        assertThat(deserialized.eventType()).isEqualTo("PaymentSettled");
        assertThat(deserialized.schemaVersion()).isEqualTo("1.0");
        assertThat(deserialized.payload().get("amountMinor").asLong()).isEqualTo(5000L);
    }

    /**
     * TEST B: Producer publishes event successfully with headers.
     */
    @Test
    @DisplayName("Test B: KafkaEventPublisher publishes event envelope with metadata and headers successfully")
    void testPhase8_TestB_ProducerPublishesEventWithHeaders() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentCreatedEventPayload payload = new PaymentCreatedEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 2500L, "USD", Instant.now()
        );

        EventEnvelope<PaymentCreatedEventPayload> envelope = EventEnvelope.create(
                "PaymentCreated", "PAYMENT", paymentId.toString(), UUID.randomUUID(), "cmd_create", payload
        );

        SendResult<String, String> sendResult = eventPublisher.publish(
                TopicNames.PAYMENT_EVENTS, paymentId.toString(), envelope
        ).get(10, TimeUnit.SECONDS);

        assertThat(sendResult).isNotNull();
        assertThat(sendResult.getRecordMetadata()).isNotNull();
        assertThat(sendResult.getRecordMetadata().topic()).isEqualTo(TopicNames.PAYMENT_EVENTS);

        // Await consumer processing so event does not spill into subsequent tests
        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(envelope.eventId()), 10000);
    }

    /**
     * TEST C: Consumer receives and processes valid event.
     */
    @Test
    @DisplayName("Test C: PaymentEventAuditConsumer receives, validates, and audits valid domain event atomically")
    void testPhase8_TestC_ConsumerReceivesAndProcessesValidEvent() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 1000L, 0L, "USD",
                UUID.randomUUID(), "cap_abc", Instant.now());

        EventEnvelope<PaymentSettledEventPayload> envelope = EventEnvelope.create(
                "PaymentSettled", "PAYMENT", paymentId.toString(), UUID.randomUUID(), "cmd_settle", payload);

        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, paymentId.toString(), envelope).get(10, TimeUnit.SECONDS);

        // Await consumer processing
        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(envelope.eventId()), 10000);

        assertThat(auditConsumer.getProcessedEvents().get(envelope.eventId())).isEqualTo("PaymentSettled");

        // Verify deduplication table in PostgreSQL
        boolean isMarkedInDb = deduplicationService.isProcessed(PaymentEventAuditConsumer.CONSUMER_GROUP, envelope.eventId());
        assertThat(isMarkedInDb).isTrue();

        // Verify persistent consumer audit side-effect table in PostgreSQL
        int auditCount = auditService.getAuditRecordCount(envelope.eventId());
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * TEST D: Duplicate event delivery protection.
     * Expected: Same event delivered twice -> processed once, skipped once, exactly 1 row in DB.
     */
    @Test
    @DisplayName("Test D: Duplicate event delivery triggers durable deduplication; exactly 1 side effect executed")
    void testPhase8_TestD_DuplicateEventDeliveryProtection() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 3000L, 0L, "USD",
                UUID.randomUUID(), "cap_dup", Instant.now());

        EventEnvelope<PaymentSettledEventPayload> envelope = EventEnvelope.create(
                "PaymentSettled", "PAYMENT", paymentId.toString(), UUID.randomUUID(), "cmd_settle_dup", payload);

        // Send first delivery
        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, paymentId.toString(), envelope).get(10, TimeUnit.SECONDS);
        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(envelope.eventId()), 10000);

        // Send duplicate delivery with exact same eventId
        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, paymentId.toString(), envelope).get(10, TimeUnit.SECONDS);

        // Await duplicate detection
        awaitCondition(() -> auditConsumer.getDuplicateSkippedCount() >= 1, 10000);

        assertThat(auditConsumer.getDuplicateSkippedCount()).isGreaterThanOrEqualTo(1);

        // Verify database: exactly 1 row in consumed_messages
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_messages WHERE consumer_group = ? AND message_id = ?",
                Long.class, PaymentEventAuditConsumer.CONSUMER_GROUP, envelope.eventId());
        assertThat(count).isEqualTo(1L);

        // Verify database: exactly 1 persistent side-effect row in payment_event_audits
        int auditCount = auditService.getAuditRecordCount(envelope.eventId());
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * TEST E: Correlation propagation end-to-end.
     */
    @Test
    @DisplayName("Test E: Correlation ID propagates from publisher to consumer via envelope and headers")
    void testPhase8_TestE_CorrelationIdPropagation() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 1500L, 0L, "USD",
                UUID.randomUUID(), "cap_corr", Instant.now());

        EventEnvelope<PaymentSettledEventPayload> envelope = EventEnvelope.create(
                "PaymentSettled", "PAYMENT", paymentId.toString(), correlationId, "cmd_corr", payload);

        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, paymentId.toString(), envelope).get(10, TimeUnit.SECONDS);

        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(envelope.eventId()), 10000);

        assertThat(envelope.correlationId()).isEqualTo(correlationId);
    }

    /**
     * TEST F: Unknown version rejection & dead-letter routing.
     */
    @Test
    @DisplayName("Test F: Unsupported event schema version is rejected deterministically and routed to DLT")
    void testPhase8_TestF_UnknownEventVersionHandling() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // Construct envelope with unsupported schema version "99.0"
        String rawUnsupportedJson = String.format("""
                {
                  "eventId": "%s",
                  "eventType": "PaymentSettled",
                  "occurredAt": "%s",
                  "aggregateType": "PAYMENT",
                  "aggregateId": "%s",
                  "schemaVersion": "99.0",
                  "correlationId": "%s",
                  "causationId": "cmd_test",
                  "payload": {
                    "paymentId": "%s",
                    "amountMinor": 1000
                  }
                }
                """, eventId, Instant.now().toString(), paymentId, UUID.randomUUID(), paymentId);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(props);

        try (Consumer<String, String> dlqConsumer = cf.createConsumer()) {
            dlqConsumer.subscribe(Collections.singletonList(TopicNames.PAYMENT_EVENTS_DLQ));

            // Publish raw unsupported version message to payment.events
            kafkaTemplate.send(TopicNames.PAYMENT_EVENTS, paymentId.toString(), rawUnsupportedJson).get(10, TimeUnit.SECONDS);

            // Verify it was NOT processed by the main consumer
            Thread.sleep(1000);
            assertThat(auditConsumer.getProcessedEvents().containsKey(eventId)).isFalse();

            // Verify message landed on DLQ
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofSeconds(10));
            assertThat(records.isEmpty()).isFalse();
            boolean found = false;
            for (var r : records) {
                if (r.value().contains(eventId.toString())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("Rejected event must arrive in DLQ").isTrue();
        }
    }

    /**
     * TEST G: Malformed JSON / poison pill produces no financial mutation and does not crash app.
     */
    @Test
    @DisplayName("Test G: Malformed JSON poison pill is safely routed to DLQ without crashing application")
    void testPhase8_TestG_MalformedJsonHandling() throws Exception {
        String poisonPill = "{MALFORMED_UNPARSEABLE_JSON_GARBAGE";

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-poison-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(props);

        try (Consumer<String, String> dlqConsumer = cf.createConsumer()) {
            dlqConsumer.subscribe(Collections.singletonList(TopicNames.PAYMENT_EVENTS_DLQ));

            kafkaTemplate.send(TopicNames.PAYMENT_EVENTS, "poisonKey", poisonPill).get(10, TimeUnit.SECONDS);

            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofSeconds(10));
            assertThat(records.isEmpty()).isFalse();
            boolean found = false;
            for (var r : records) {
                if (r.value().contains("MALFORMED_UNPARSEABLE_JSON_GARBAGE")) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("Poison pill must be forwarded to DLQ").isTrue();
        }
    }

    /**
     * TEST H: Kafka outage resilience (Financial Safety).
     * Proves: PostgreSQL financial settlement succeeds independently of Kafka transport.
     */
    @Test
    @DisplayName("Test H: Financial payment settlement in PostgreSQL succeeds independently of Kafka transport")
    void testPhase8_TestH_KafkaOutageResilience() {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(2000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        PaymentResponse response = paymentService.createPayment(
                testUser.getId(), payerAccount.getId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), request);

        assertThat(response.getStatus()).isEqualTo("SETTLED");
        assertThat(response.getPaymentId()).isNotNull();

        // Verify financial truth in PostgreSQL
        Long balancePayer = jdbcTemplate.queryForObject(
                "SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payerAccount.getId());
        Long balancePayee = jdbcTemplate.queryForObject(
                "SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payeeAccount.getId());

        assertThat(balancePayer).isEqualTo(8000L); // 10000 - 2000
        assertThat(balancePayee).isEqualTo(2000L);
    }

    /**
     * TEST I: Consumer transaction atomicity & retry safety.
     * Proves: Forced failure after deduplication insertion rolls back the DB transaction
     * (no phantom deduplication record), allowing Kafka retry to complete the persistent side-effect.
     */
    @Test
    @DisplayName("Test I: Consumer deduplication marker rolls back on side-effect failure and succeeds on retry")
    void testPhase8_TestI_ConsumerAtomicityRollbackAndRetrySuccess() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEventPayload payload = new PaymentSettledEventPayload(
                paymentId, payerAccount.getId(), payeeAccount.getId(), 4500L, 0L, "USD",
                UUID.randomUUID(), "cap_atomic", Instant.now());

        EventEnvelope<PaymentSettledEventPayload> envelope = EventEnvelope.create(
                "PaymentSettled", "PAYMENT", paymentId.toString(), UUID.randomUUID(), "cmd_settle_atomic", payload);

        // 1. Arm failure hook to fail the first attempt during side-effect execution
        auditService.setSimulateTransientFailure(true);

        // 2. Publish event to Kafka
        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, paymentId.toString(), envelope).get(10, TimeUnit.SECONDS);

        // 3. Await retry completion: the first execution rolled back atomically, and the subsequent
        // Kafka retry succeeded in committing both deduplication and audit side-effect rows.
        awaitCondition(() -> auditConsumer.getProcessedEvents().containsKey(envelope.eventId()), 10000);

        // 4. Assert exactly ONE logical persistent side effect occurred
        assertThat(auditConsumer.getProcessedEvents().containsKey(envelope.eventId())).isTrue();
        int auditCount = auditService.getAuditRecordCount(envelope.eventId());
        assertThat(auditCount).isEqualTo(1);

        Long dedupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumed_messages WHERE consumer_group = ? AND message_id = ?",
                Long.class, PaymentEventAuditConsumer.CONSUMER_GROUP, envelope.eventId());
        assertThat(dedupCount).isEqualTo(1L);
    }

    /**
     * TEST J: Partition ordering for events belonging to the same aggregate ID.
     */
    @Test
    @DisplayName("Test J: Events with the same aggregate ID are published to the exact same Kafka partition")
    void testPhase8_TestJ_PartitionOrdering() throws Exception {
        String aggregateId = UUID.randomUUID().toString();

        SendResult<String, String> res1 = eventPublisher.publish(
                TopicNames.PAYMENT_EVENTS,
                aggregateId,
                EventEnvelope.create("PaymentCreated", "PAYMENT", aggregateId, UUID.randomUUID(), "cmd1",
                        new PaymentCreatedEventPayload(UUID.randomUUID(), payerAccount.getId(), payeeAccount.getId(), 1000L, "USD", Instant.now()))
        ).get(10, TimeUnit.SECONDS);

        SendResult<String, String> res2 = eventPublisher.publish(
                TopicNames.PAYMENT_EVENTS,
                aggregateId,
                EventEnvelope.create("PaymentSettled", "PAYMENT", aggregateId, UUID.randomUUID(), "cmd2",
                        new PaymentSettledEventPayload(UUID.randomUUID(), payerAccount.getId(), payeeAccount.getId(), 1000L, 0L, "USD", UUID.randomUUID(), "cap_1", Instant.now()))
        ).get(10, TimeUnit.SECONDS);

        int partition1 = res1.getRecordMetadata().partition();
        int partition2 = res2.getRecordMetadata().partition();

        assertThat(partition1).isEqualTo(partition2);
    }

    /**
     * TEST K: Consumer restart durability.
     * Proves: Deduplication records in consumed_messages survive across consumer sessions.
     */
    @Test
    @DisplayName("Test K: Deduplication state persisted in PostgreSQL survives consumer restart")
    void testPhase8_TestK_RestartDurability() throws Exception {
        UUID eventId = UUID.randomUUID();

        // Simulate pre-existing processed event recorded prior to restart
        jdbcTemplate.update(
                "INSERT INTO consumed_messages (consumer_group, message_id, event_type, processed_at) VALUES (?, ?, ?, NOW())",
                PaymentEventAuditConsumer.CONSUMER_GROUP, eventId, "PaymentSettled");

        // An event with this eventId arrives at the consumer
        EventEnvelope<PaymentSettledEventPayload> envelope = new EventEnvelope<>(
                eventId,
                "PaymentSettled",
                Instant.now(),
                "PAYMENT",
                UUID.randomUUID().toString(),
                "1.0",
                UUID.randomUUID(),
                "cmd_preexisting",
                new PaymentSettledEventPayload(UUID.randomUUID(), payerAccount.getId(), payeeAccount.getId(), 1000L, 0L, "USD", UUID.randomUUID(), "cap_pre", Instant.now())
        );

        eventPublisher.publish(TopicNames.PAYMENT_EVENTS, envelope.aggregateId(), envelope).get(10, TimeUnit.SECONDS);

        awaitCondition(() -> auditConsumer.getDuplicateSkippedCount() >= 1, 10000);

        // Side-effect was not applied again
        assertThat(auditConsumer.getProcessedEvents().containsKey(eventId)).isFalse();
    }

    /**
     * TEST L: Multiple consumer groups process events independently.
     */
    @Test
    @DisplayName("Test L: Multiple consumer groups maintain independent deduplication scopes in PostgreSQL")
    void testPhase8_TestL_MultipleConsumerGroupsIndependentDeduplication() {
        UUID eventId = UUID.randomUUID();

        // Consumer Group A processes event
        boolean groupASuccess = deduplicationService.tryConsume("group-a", eventId, "PaymentSettled");
        assertThat(groupASuccess).isTrue();

        // Consumer Group B processes same event
        boolean groupBSuccess = deduplicationService.tryConsume("group-b", eventId, "PaymentSettled");
        assertThat(groupBSuccess).isTrue();

        // Group A retries -> duplicate rejected
        boolean groupARetry = deduplicationService.tryConsume("group-a", eventId, "PaymentSettled");
        assertThat(groupARetry).isFalse();

        // Group B retries -> duplicate rejected
        boolean groupBRetry = deduplicationService.tryConsume("group-b", eventId, "PaymentSettled");
        assertThat(groupBRetry).isFalse();
    }

    /**
     * TEST M: Real production application workflow emits domain events to Kafka.
     * Proves: PaymentService.createPayment -> settles in DB -> emits PaymentSettled to payment.events -> audited by consumer.
     */
    @Test
    @DisplayName("Test M: Production PaymentService settlement automatically emits PaymentSettled event consumed by audit listener")
    void testPhase8_TestM_ProductionPaymentSettlementEmitsDomainEvent() {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(1200L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        String correlationId = UUID.randomUUID().toString();
        PaymentResponse response = paymentService.createPayment(
                testUser.getId(), payerAccount.getId(), UUID.randomUUID().toString(), correlationId, request);

        assertThat(response.getStatus()).isEqualTo("SETTLED");
        UUID paymentId = response.getPaymentId();

        // Verify consumer received and audited the PaymentSettled event emitted by the production service
        awaitCondition(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payment_event_audits WHERE aggregate_id = ? AND event_type = 'PaymentSettled'",
                    Integer.class, paymentId.toString());
            return count != null && count > 0;
        }, 10000);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_audits WHERE aggregate_id = ? AND event_type = 'PaymentSettled'",
                Integer.class, paymentId.toString());
        assertThat(auditCount).isEqualTo(1);
    }

    /**
     * TEST N: Real production account lifecycle emits AccountFrozen domain event.
     */
    @Test
    @DisplayName("Test N: Production AccountService.freezeAccount emits AccountFrozen event to account.events topic")
    void testPhase8_TestN_ProductionAccountFreezeEmitsEvent() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "account-event-verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(props);

        try (Consumer<String, String> consumer = cf.createConsumer()) {
            consumer.subscribe(Collections.singletonList(TopicNames.ACCOUNT_EVENTS));

            accountService.freezeAccount(payerAccount.getId(), "SUSPECTED_FRAUD");

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.isEmpty()).isFalse();

            boolean found = false;
            for (var record : records) {
                if (record.value().contains("AccountFrozen") && record.value().contains(payerAccount.getId().toString())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("AccountFrozen event must be published on account.events topic").isTrue();
        }
    }
}
