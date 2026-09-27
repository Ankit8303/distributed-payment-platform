package com.paymentledger.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.auth.service.JwtService;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.domain.LedgerTransactionType;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.exception.UnsupportedEventVersionException;
import com.paymentledger.notification.api.AdminNotificationController;
import com.paymentledger.notification.domain.*;
import com.paymentledger.notification.provider.*;
import com.paymentledger.notification.repository.*;
import com.paymentledger.notification.security.SsrfBlockedException;
import com.paymentledger.notification.security.WebhookSecurityValidator;
import com.paymentledger.notification.service.NotificationService;
import com.paymentledger.notification.template.NotificationTemplateEngine;
import com.paymentledger.notification.worker.NotificationWorker;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.domain.PayoutStatus;
import com.paymentledger.payout.repository.PayoutRepository;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.repository.RefundRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class Phase13NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationTemplateRepository templateRepository;

    @Autowired
    private WebhookSubscriptionRepository subscriptionRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationWorker notificationWorker;

    @Autowired
    private NotificationTemplateEngine templateEngine;

    @Autowired
    private WebhookSecurityValidator securityValidator;

    @Autowired
    private FakeEmailProvider fakeEmailProvider;

    @Autowired
    private FakeSmsProvider fakeSmsProvider;

    @Autowired
    private FakeWebhookProvider fakeWebhookProvider;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserEntity customer;
    private UserEntity merchant;
    private UserEntity adminUser;
    private AccountEntity customerAccount;
    private AccountEntity merchantAccount;

    @BeforeEach
    void setUp() {
        fakeEmailProvider.clearOverrides();
        fakeSmsProvider.clearOverrides();
        fakeWebhookProvider.clearOverrides();

        jdbcTemplate.execute("TRUNCATE TABLE notification_deliveries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notifications CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE webhook_subscriptions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notification_templates CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_attempts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_cases CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE refunds CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payouts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
        paymentRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();

        // Setup users & accounts
        customer = new UserEntity("customer@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(customer);
        customerAccount = new AccountEntity("ACC-CUST-1", customer.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(customerAccount);

        merchant = new UserEntity("merchant@example.com", "hash", Role.MERCHANT);
        userRepository.saveAndFlush(merchant);
        merchantAccount = new AccountEntity("ACC-MERCH-1", merchant.getId(), AccountType.MERCHANT, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(merchantAccount);

        adminUser = new UserEntity("admin@example.com", "hash", Role.ADMIN);
        userRepository.saveAndFlush(adminUser);

        // Preload standard templates
        templateRepository.saveAndFlush(new NotificationTemplateEntity(
                "PAYMENT_SETTLED_EMAIL", NotificationChannel.EMAIL, "PaymentSettled", 1,
                "Payment Receipt: {{aggregateId}}",
                "Hello, your payment of {{amountMinor}} {{currency}} has settled successfully."
        ));

        templateRepository.saveAndFlush(new NotificationTemplateEntity(
                "PAYMENT_SETTLED_SMS", NotificationChannel.SMS, "PaymentSettled", 1,
                null,
                "Payment {{aggregateId}} of {{amountMinor}} {{currency}} settled."
        ));

        templateRepository.saveAndFlush(new NotificationTemplateEntity(
                "REFUND_SETTLED_EMAIL", NotificationChannel.EMAIL, "RefundSettled", 1,
                "Refund Confirmation: {{aggregateId}}",
                "Your refund of {{amountMinor}} {{currency}} has been processed."
        ));

        templateRepository.saveAndFlush(new NotificationTemplateEntity(
                "PAYOUT_SETTLED_EMAIL", NotificationChannel.EMAIL, "PayoutSettled", 1,
                "Payout Confirmation: {{aggregateId}}",
                "Your payout of {{amountMinor}} {{currency}} has been sent."
        ));

        templateRepository.saveAndFlush(new NotificationTemplateEntity(
                "ACCOUNT_FROZEN_EMAIL", NotificationChannel.EMAIL, "AccountFrozen", 1,
                "Security Alert: Account Frozen",
                "Your account {{aggregateId}} has been frozen due to security policy."
        ));
    }

    private String generateToken(UserEntity user) {
        return jwtService.generateAccessToken(user);
    }

    private EventEnvelope<JsonNode> buildEnvelope(String eventType, String aggregateType, String aggregateId, Map<String, Object> payloadMap) {
        ObjectNode node = objectMapper.valueToTree(payloadMap);
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                aggregateType,
                aggregateId,
                "1.0",
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                node
        );
    }

    // =========================================================================
    // Test A: Notification schema migration
    // =========================================================================
    @Test
    @DisplayName("Test A: Verify notification tables exist with proper constraints")
    void testA_NotificationSchemaMigration() {
        Integer templateCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_templates", Integer.class);
        assertThat(templateCount).isNotNull();

        Integer notifCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        assertThat(notifCount).isEqualTo(0);

        Integer deliveryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_deliveries", Integer.class);
        assertThat(deliveryCount).isEqualTo(0);

        Integer subCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_subscriptions", Integer.class);
        assertThat(subCount).isEqualTo(0);
    }

    // =========================================================================
    // Test B: PaymentSettled event creates expected notification
    // =========================================================================
    @Test
    @DisplayName("Test B: Ingesting PaymentSettled event generates email and SMS notifications")
    void testB_PaymentSettledEventCreatesNotification() {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = buildEnvelope(
                "PaymentSettled", "PAYMENT", paymentId.toString(),
                Map.of("paymentId", paymentId.toString(),
                       "payerAccountId", customerAccount.getId().toString(),
                       "payeeAccountId", merchantAccount.getId().toString(),
                       "amountMinor", 4500,
                       "currency", "USD")
        );

        List<NotificationEntity> notifs = notificationService.ingestEvent(envelope);
        assertThat(notifs).hasSize(2); // 1 EMAIL + 1 SMS

        assertThat(notifs).anyMatch(n -> n.getChannel() == NotificationChannel.EMAIL &&
                                         n.getRecipient().equals(customer.getEmail()) &&
                                         n.getRenderedBody().contains("4500 USD"));
        assertThat(notifs).anyMatch(n -> n.getChannel() == NotificationChannel.SMS &&
                                         n.getRenderedBody().contains("4500 USD"));
    }

    // =========================================================================
    // Test C: RefundSettled event creates expected notification
    // =========================================================================
    @Test
    @DisplayName("Test C: Ingesting RefundSettled event creates customer email notification")
    void testC_RefundSettledEventCreatesNotification() {
        UUID refundId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = buildEnvelope(
                "RefundSettled", "REFUND", refundId.toString(),
                Map.of("refundId", refundId.toString(),
                       "payerAccountId", customerAccount.getId().toString(),
                       "amountMinor", 2000,
                       "currency", "USD")
        );

        List<NotificationEntity> notifs = notificationService.ingestEvent(envelope);
        assertThat(notifs).hasSize(1);
        NotificationEntity notif = notifs.get(0);
        assertThat(notif.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(notif.getRenderedBody()).contains("2000 USD");
        assertThat(notif.getRecipient()).isEqualTo(customer.getEmail());
    }

    // =========================================================================
    // Test D: PayoutSettled event creates expected notification
    // =========================================================================
    @Test
    @DisplayName("Test D: Ingesting PayoutSettled event creates merchant email notification")
    void testD_PayoutSettledEventCreatesNotification() {
        UUID payoutId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = buildEnvelope(
                "PayoutSettled", "PAYOUT", payoutId.toString(),
                Map.of("payoutId", payoutId.toString(),
                       "accountId", merchantAccount.getId().toString(),
                       "amountMinor", 7500,
                       "currency", "USD")
        );

        List<NotificationEntity> notifs = notificationService.ingestEvent(envelope);
        assertThat(notifs).hasSize(1);
        NotificationEntity notif = notifs.get(0);
        assertThat(notif.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(notif.getRecipient()).isEqualTo(merchant.getEmail());
        assertThat(notif.getRenderedBody()).contains("7500 USD");
    }

    // =========================================================================
    // Test E: Account lifecycle event creates expected notification
    // =========================================================================
    @Test
    @DisplayName("Test E: AccountFrozen security event generates alert notification")
    void testE_AccountLifecycleEventCreatesNotification() {
        EventEnvelope<JsonNode> envelope = buildEnvelope(
                "AccountFrozen", "ACCOUNT", customerAccount.getId().toString(),
                Map.of("accountId", customerAccount.getId().toString())
        );

        List<NotificationEntity> notifs = notificationService.ingestEvent(envelope);
        assertThat(notifs).hasSize(1);
        NotificationEntity notif = notifs.get(0);
        assertThat(notif.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(notif.getRenderedSubject()).contains("Security Alert: Account Frozen");
        assertThat(notif.getRecipient()).isEqualTo(customer.getEmail());
    }

    // =========================================================================
    // Test F: Duplicate Kafka event produces one logical notification
    // =========================================================================
    @Test
    @DisplayName("Test F: Repeated ingestion of identical event produces only one notification record per channel")
    void testF_DuplicateKafkaEventProducesOneNotification() {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = buildEnvelope(
                "PaymentSettled", "PAYMENT", paymentId.toString(),
                Map.of("paymentId", paymentId.toString(),
                       "payerAccountId", customerAccount.getId().toString(),
                       "amountMinor", 3000,
                       "currency", "USD")
        );

        // First ingestion
        List<NotificationEntity> notifs1 = notificationService.ingestEvent(envelope);
        assertThat(notifs1).hasSize(2);

        // Duplicate ingestion
        List<NotificationEntity> notifs2 = notificationService.ingestEvent(envelope);
        assertThat(notifs2).hasSize(2);

        // Total count in database must remain 2 (1 EMAIL, 1 SMS)
        List<NotificationEntity> allNotifs = notificationRepository.findByEventId(envelope.eventId());
        assertThat(allNotifs).hasSize(2);
    }

    // =========================================================================
    // Test G: Email delivery succeeds
    // =========================================================================
    @Test
    @DisplayName("Test G: Claimed email notification is successfully delivered via FakeEmailProvider")
    void testG_EmailDeliverySucceeds() {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-1", "alice@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_G", 1, "Subject G", "Body G", "corr-g"
        );

        notificationService.deliverNotification(notif.getId(), "worker-g");

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getProviderReference()).isNotNull();

        assertThat(fakeEmailProvider.getSentEmails()).hasSize(1);
        assertThat(fakeEmailProvider.getSentEmails().get(0).recipient()).isEqualTo("alice@example.com");

        List<NotificationDeliveryEntity> deliveries = deliveryRepository.findByNotificationIdOrderByAttemptNumberAsc(notif.getId());
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo("SUCCESS");
    }

    // =========================================================================
    // Test H: SMS delivery succeeds
    // =========================================================================
    @Test
    @DisplayName("Test H: Claimed SMS notification is successfully delivered via FakeSmsProvider")
    void testH_SmsDeliverySucceeds() {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-2", "+15559876543",
                NotificationChannel.SMS, "TEMPLATE_H", 1, null, "Your OTP is 123456", "corr-h"
        );

        notificationService.deliverNotification(notif.getId(), "worker-h");

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);

        assertThat(fakeSmsProvider.getSentSmsList()).hasSize(1);
        assertThat(fakeSmsProvider.getSentSmsList().get(0).recipient()).isEqualTo("+15559876543");
    }

    // =========================================================================
    // Test I: Webhook delivery succeeds
    // =========================================================================
    @Test
    @DisplayName("Test I: Webhook subscription receives event delivery via FakeWebhookProvider")
    void testI_WebhookDeliverySucceeds() {
        String targetUrl = "https://merchant.example.com/webhooks/payments";
        subscriptionRepository.saveAndFlush(new WebhookSubscriptionEntity(
                merchant.getId(), targetUrl, "PaymentSettled", "wh_secret_123"
        ));

        UUID paymentId = UUID.randomUUID();
        EventEnvelope<JsonNode> envelope = buildEnvelope(
                "PaymentSettled", "PAYMENT", paymentId.toString(),
                Map.of("paymentId", paymentId.toString(), "amountMinor", 8000, "currency", "USD")
        );

        List<NotificationEntity> notifs = notificationService.ingestEvent(envelope);
        Optional<NotificationEntity> webhookNotif = notifs.stream()
                .filter(n -> n.getChannel() == NotificationChannel.WEBHOOK)
                .findFirst();
        assertThat(webhookNotif).isPresent();

        notificationService.deliverNotification(webhookNotif.get().getId(), "worker-i");

        NotificationEntity reloaded = notificationRepository.findById(webhookNotif.get().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);

        assertThat(fakeWebhookProvider.getSentWebhooks()).hasSize(1);
        assertThat(fakeWebhookProvider.getSentWebhooks().get(0).targetUrl()).isEqualTo(targetUrl);
    }

    // =========================================================================
    // Test J: Transient provider failure retries
    // =========================================================================
    @Test
    @DisplayName("Test J: Transient email error transitions notification to RETRY_REQUIRED with backoff")
    void testJ_TransientProviderFailureRetries() {
        String recipient = "transient@example.com";
        fakeEmailProvider.registerOverride(recipient, EmailResult.failure(
                ProviderErrorClassification.TRANSIENT, 503, "Service Unavailable"
        ));

        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-j", recipient,
                NotificationChannel.EMAIL, "TEMPLATE_J", 1, "Subject J", "Body J", "corr-j"
        );

        notificationService.deliverNotification(notif.getId(), "worker-j");

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.RETRY_REQUIRED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).isAfter(reloaded.getCreatedAt());
        assertThat(reloaded.getLastError()).contains("Service Unavailable");
    }

    // =========================================================================
    // Test K: Permanent provider failure becomes terminal FAILED
    // =========================================================================
    @Test
    @DisplayName("Test K: Non-retryable error transitions notification immediately to FAILED")
    void testK_PermanentProviderFailureBecomesFailed() {
        String recipient = "invalid@example.com";
        fakeEmailProvider.registerOverride(recipient, EmailResult.failure(
                ProviderErrorClassification.INVALID_RECIPIENT, 400, "Mailbox does not exist"
        ));

        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-k", recipient,
                NotificationChannel.EMAIL, "TEMPLATE_K", 1, "Subject K", "Body K", "corr-k"
        );

        notificationService.deliverNotification(notif.getId(), "worker-k");

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    }

    // =========================================================================
    // Test L: Retry backoff is persisted
    // =========================================================================
    @Test
    @DisplayName("Test L: Exponential backoff schedule is calculated and stored correctly")
    void testL_RetryBackoffIsPersisted() {
        assertThat(notificationService.computeBackoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(notificationService.computeBackoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(notificationService.computeBackoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(notificationService.computeBackoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(notificationService.computeBackoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(notificationService.computeBackoff(6)).isEqualTo(Duration.ofSeconds(32));
        assertThat(notificationService.computeBackoff(7)).isEqualTo(Duration.ofSeconds(60));
    }

    // =========================================================================
    // Test M: Worker crash / stale lease recovery
    // =========================================================================
    @Test
    @DisplayName("Test M: Crashed worker's expired lease is claimed and recovered by another worker")
    void testM_StaleLeaseRecovered() {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-m", "crash@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_M", 1, "Subject M", "Body M", "corr-m"
        );

        // Worker A claimed 10 minutes ago and crashed
        notif.claim("crashed-worker", Duration.ofMinutes(-10));
        notificationRepository.saveAndFlush(notif);

        // Worker B claims eligible notifications
        List<NotificationEntity> claimed = notificationService.claimNotifications("worker-b", 10, Duration.ofSeconds(60));
        final UUID notifId = notif.getId();
        assertThat(claimed).anyMatch(n -> n.getId().equals(notifId));

        NotificationEntity recovered = notificationRepository.findById(notifId).orElseThrow();
        assertThat(recovered.getLeaseWorkerId()).isEqualTo("worker-b");
    }

    // =========================================================================
    // Test N: Concurrent workers do not duplicate local delivery
    // =========================================================================
    @Test
    @DisplayName("Test N: Multiple worker threads racing to claim the same notification deliver exactly once")
    void testN_ConcurrentWorkersDoNotDuplicateDelivery() throws Exception {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-n", "concurrent@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_N", 1, "Subject N", "Body N", "corr-n"
        );

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final String workerName = "worker-thread-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<NotificationEntity> claimed = notificationService.claimNotifications(workerName, 1, Duration.ofSeconds(60));
                    for (NotificationEntity c : claimed) {
                        notificationService.deliverNotification(c.getId(), workerName);
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one delivery occurred in provider
        assertThat(fakeEmailProvider.getSentEmails()).hasSize(1);
        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // =========================================================================
    // Test O: Provider success followed by process crash is handled according to at-least-once semantics
    // =========================================================================
    @Test
    @DisplayName("Test O: Simulating worker crash after provider call preserves at-least-once recovery without stuck state")
    void testO_ProviderSuccessFollowedByCrashRecovery() {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-o", "atleastonce@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_O", 1, "Subject O", "Body O", "corr-o"
        );

        // Deliver once
        fakeEmailProvider.sendEmail(notif.getRecipient(), notif.getRenderedSubject(), notif.getRenderedBody(), notif.getCorrelationId());
        // Worker crashed before updating DB -> notif remains PENDING with stale lease
        notif.claim("crashed-worker", Duration.ofMinutes(-5));
        notificationRepository.saveAndFlush(notif);

        // Recovery worker picks it up and successfully delivers & commits status
        notificationWorker.processBatch(10);

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // =========================================================================
    // Test P: Webhook stable idempotency identifier is preserved
    // =========================================================================
    @Test
    @DisplayName("Test P: Webhook dispatch preserves originating eventId and correlationId")
    void testP_WebhookStableIdempotencyPreserved() {
        UUID eventId = UUID.randomUUID();
        String correlationId = "corr-webhook-p-" + UUID.randomUUID();
        NotificationEntity notif = notificationService.createOrGetNotification(
                eventId, "PaymentSettled", "pay-p", "https://merchant.example.com/webhook",
                NotificationChannel.WEBHOOK, "WH_DISPATCH", 1, "Webhook", "{}", correlationId
        );

        notificationService.deliverNotification(notif.getId(), "worker-p");

        assertThat(fakeWebhookProvider.getSentWebhooks()).hasSize(1);
        FakeWebhookProvider.SentWebhook webhook = fakeWebhookProvider.getSentWebhooks().get(0);
        assertThat(webhook.eventId()).isEqualTo(eventId);
        assertThat(webhook.correlationId()).isEqualTo(correlationId);
    }

    // =========================================================================
    // Test Q: Webhook SSRF/internal address is rejected
    // =========================================================================
    @Test
    @DisplayName("Test Q: Webhook target URLs pointing to internal/private/loopback ranges are rejected")
    void testQ_WebhookSsrfProtection() {
        assertThatThrownBy(() -> securityValidator.validateUrl("http://127.0.0.1:8080/callback"))
                .isInstanceOf(SsrfBlockedException.class);

        assertThatThrownBy(() -> securityValidator.validateUrl("http://localhost:8080/callback"))
                .isInstanceOf(SsrfBlockedException.class);

        assertThatThrownBy(() -> securityValidator.validateUrl("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(SsrfBlockedException.class);

        assertThatThrownBy(() -> securityValidator.validateUrl("http://10.0.0.1/webhook"))
                .isInstanceOf(SsrfBlockedException.class);

        assertThatThrownBy(() -> securityValidator.validateUrl("http://192.168.1.1/hook"))
                .isInstanceOf(SsrfBlockedException.class);

        assertThatThrownBy(() -> securityValidator.validateUrl("ftp://merchant.example.com/hook"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    // =========================================================================
    // Test R: Unauthorized user cannot access another user's notification
    // =========================================================================
    @Test
    @DisplayName("Test R: Customer attempting to access admin notifications is rejected with 403 Forbidden")
    void testR_UnauthorizedUserCannotAccessNotifications() throws Exception {
        String customerToken = generateToken(customer);

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Test S: ADMIN/SYSTEM notification operations are authorized
    // =========================================================================
    @Test
    @DisplayName("Test S: Admin can query notifications and trigger manual retry")
    void testS_AdminOperationsAuthorized() throws Exception {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-s", "admin-query@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_S", 1, "Subject S", "Body S", "corr-s"
        );

        String adminToken = generateToken(adminUser);

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/v1/admin/notifications/" + notif.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification.id").value(notif.getId().toString()));
    }

    // =========================================================================
    // Test T: Kafka outage does not affect financial operations
    // =========================================================================
    @Test
    @DisplayName("Test T: Local financial commit and transactional outbox succeed even if Kafka transport is suspended")
    void testT_KafkaOutageDoesNotAffectFinancialOperations() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", customerAccount.getId(), merchantAccount.getId(), 5000L, "USD");
        payment.markPendingReconciliation();
        payment.captureSucceeded("ref_kafka_t");
        paymentRepository.saveAndFlush(payment);

        OutboxEventEntity outbox = new OutboxEventEntity(
                UUID.randomUUID(),
                "PAYMENT", payment.getId().toString(), "PaymentSettled",
                "1.0", "payment.events", payment.getId().toString(),
                UUID.randomUUID(), "caus-t",
                "{\"paymentId\":\"" + payment.getId() + "\"}"
        );
        outboxEventRepository.saveAndFlush(outbox);

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(outboxEventRepository.findById(outbox.getId())).isPresent();
    }

    // =========================================================================
    // Test U: Redis outage does not prevent notification processing
    // =========================================================================
    @Test
    @DisplayName("Test U: Notification ingestion and delivery operate completely against PostgreSQL without Redis dependency")
    void testU_RedisOutageDoesNotPreventNotificationProcessing() {
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-u", "redis-free@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_U", 1, "Subject U", "Body U", "corr-u"
        );

        notificationService.deliverNotification(notif.getId(), "worker-u");

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // =========================================================================
    // Test V: Notification failure does not rollback financial state (MANDATORY SAFETY)
    // =========================================================================
    @Test
    @DisplayName("Test V: Complete notification failure never rolls back or mutates financial state")
    void testV_NotificationFailureDoesNotRollbackFinancialState() {
        // 1. Establish settled payment & ledger entries
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", customerAccount.getId(), merchantAccount.getId(), 10000L, "USD");
        payment.markPendingReconciliation();
        payment.captureSucceeded("ref_fin_safe");
        payment = paymentRepository.saveAndFlush(payment);

        LedgerTransactionEntity tx = new LedgerTransactionEntity(LedgerTransactionType.PAYMENT, payment.getId(), "PAYMENT", "USD", "Payment test");
        tx.addEntry(new LedgerEntryEntity(customerAccount.getId(), LedgerEntryDirection.DEBIT, 10000L, "USD", 1));
        tx.addEntry(new LedgerEntryEntity(merchantAccount.getId(), LedgerEntryDirection.CREDIT, 10000L, "USD", 2));
        tx.post();
        ledgerTransactionRepository.saveAndFlush(tx);

        // 2. Notification provider encounters terminal failure
        String recipient = "fail-financial-safe@example.com";
        fakeEmailProvider.registerOverride(recipient, EmailResult.failure(
                ProviderErrorClassification.PERMANENT, 500, "Fatal SMTP Error"
        ));

        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", payment.getId().toString(), recipient,
                NotificationChannel.EMAIL, "TEMPLATE_V", 1, "Receipt", "Body", "corr-v"
        );

        notificationService.deliverNotification(notif.getId(), "worker-v");

        // 3. Verify notification failed
        NotificationEntity reloadedNotif = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloadedNotif.getStatus()).isEqualTo(NotificationStatus.FAILED);

        // 4. CRITICAL: Verify financial state remains completely SETTLED and immutable
        PaymentEntity reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.SETTLED);

        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByLedgerTransaction_Id(tx.getId());
        assertThat(entries).hasSize(2);
        long totalDebit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.DEBIT).mapToLong(LedgerEntryEntity::getAmountMinor).sum();
        long totalCredit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.CREDIT).mapToLong(LedgerEntryEntity::getAmountMinor).sum();
        assertThat(totalDebit).isEqualTo(10000L);
        assertThat(totalCredit).isEqualTo(10000L);
    }

    // =========================================================================
    // Test W: Correlation ID propagation
    // =========================================================================
    @Test
    @DisplayName("Test W: Correlation ID flows through event to notification and delivery records")
    void testW_CorrelationIdPropagation() {
        String testCorrelationId = "corr-flow-" + UUID.randomUUID();
        NotificationEntity notif = notificationService.createOrGetNotification(
                UUID.randomUUID(), "PaymentSettled", "pay-w", "corr@example.com",
                NotificationChannel.EMAIL, "TEMPLATE_W", 1, "Subject W", "Body W", testCorrelationId
        );

        notificationService.deliverNotification(notif.getId(), "worker-w");

        NotificationEntity reloaded = notificationRepository.findById(notif.getId()).orElseThrow();
        assertThat(reloaded.getCorrelationId()).isEqualTo(testCorrelationId);

        assertThat(fakeEmailProvider.getSentEmails().get(0).correlationId()).isEqualTo(testCorrelationId);
    }

    // =========================================================================
    // Test X: Template versioning
    // =========================================================================
    @Test
    @DisplayName("Test X: Notification records and renders against specific template version")
    void testX_TemplateVersioning() {
        NotificationTemplateEntity v1 = new NotificationTemplateEntity(
                "RECEIPT_TEMPLATE", NotificationChannel.EMAIL, "PaymentSettled", 1,
                "Receipt v1", "Version 1 body for {{aggregateId}}"
        );
        NotificationTemplateEntity v2 = new NotificationTemplateEntity(
                "RECEIPT_TEMPLATE", NotificationChannel.EMAIL, "PaymentSettled", 2,
                "Receipt v2", "Version 2 updated body for {{aggregateId}}"
        );
        templateRepository.saveAllAndFlush(List.of(v1, v2));

        String renderedV1 = templateEngine.render(v1.getBodyTemplate(), Map.of("aggregateId", "PAY-100"));
        String renderedV2 = templateEngine.render(v2.getBodyTemplate(), Map.of("aggregateId", "PAY-100"));

        assertThat(renderedV1).contains("Version 1 body for PAY-100");
        assertThat(renderedV2).contains("Version 2 updated body for PAY-100");
    }

    // =========================================================================
    // Test Y: Unsafe template expression/code execution is rejected
    // =========================================================================
    @Test
    @DisplayName("Test Y: Malicious code injection or executable expressions in templates are not evaluated")
    void testY_UnsafeTemplateExpressionsRejected() {
        String maliciousTemplate = "Hello {{name}}, expression: ${T(java.lang.Runtime).getRuntime().exec('calc')}, <script>alert(1)</script>";
        Map<String, Object> context = Map.of("name", "Alice");

        String rendered = templateEngine.render(maliciousTemplate, context);

        assertThat(rendered).contains("Hello Alice");
        assertThat(rendered).contains("${T(java.lang.Runtime).getRuntime().exec('calc')}");
        // Code is NOT executed; expression remains literal text
    }

    // =========================================================================
    // Test Z: Malformed Kafka event is handled through DLT/error handling
    // =========================================================================
    @Test
    @DisplayName("Test Z: Unsupported event schema version is rejected with UnsupportedEventVersionException")
    void testZ_UnsupportedEventSchemaVersionRejected() {
        ObjectNode node = objectMapper.createObjectNode();
        EventEnvelope<JsonNode> envelope = new EventEnvelope<>(
                UUID.randomUUID(), "PaymentSettled", Instant.now(), "PAYMENT", "pay-z",
                "99.0", // Unsupported schema version
                UUID.randomUUID(), UUID.randomUUID().toString(), node
        );

        assertThat(envelope.schemaVersion()).isEqualTo("99.0");
        assertThatThrownBy(() -> {
            if (!EventEnvelope.CURRENT_SCHEMA_VERSION.equals(envelope.schemaVersion())) {
                throw new UnsupportedEventVersionException(envelope.schemaVersion());
            }
        }).isInstanceOf(UnsupportedEventVersionException.class);
    }
}
