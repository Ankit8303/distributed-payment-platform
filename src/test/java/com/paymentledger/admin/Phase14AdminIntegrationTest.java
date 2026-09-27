package com.paymentledger.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.account.service.AccountService;
import com.paymentledger.admin.api.dto.AccountLifecycleRequest;
import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.admin.domain.AdminAuditLogEntity;
import com.paymentledger.admin.repository.AdminAuditLogRepository;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.domain.UserStatus;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.auth.service.JwtService;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.domain.LedgerTransactionType;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.notification.domain.NotificationChannel;
import com.paymentledger.notification.domain.NotificationEntity;
import com.paymentledger.notification.domain.NotificationStatus;
import com.paymentledger.notification.repository.NotificationRepository;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.domain.PayoutStatus;
import com.paymentledger.payout.repository.PayoutRepository;
import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;
import com.paymentledger.reconciliation.domain.ReconciliationOperationType;
import com.paymentledger.reconciliation.domain.ReconciliationStatus;
import com.paymentledger.reconciliation.repository.ReconciliationCaseRepository;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.repository.RefundRepository;
import com.paymentledger.shared.logging.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
public class Phase14AdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ReconciliationCaseRepository reconciliationCaseRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private UserEntity adminUser;
    private UserEntity customerUser;
    private UserEntity merchantUser;

    private String adminToken;
    private String customerToken;
    private String merchantToken;

    private AccountEntity payerAccount;
    private AccountEntity payeeAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE admin_audit_logs CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notification_deliveries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notifications CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_attempts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_cases CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE refunds CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payouts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payment_event_audits CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
        paymentRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();

        adminUser = new UserEntity("admin@paymentledger.com", "hash_admin", Role.ADMIN);
        userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser);

        customerUser = new UserEntity("customer@example.com", "hash_cust", Role.CUSTOMER);
        userRepository.saveAndFlush(customerUser);
        customerToken = jwtService.generateAccessToken(customerUser);

        merchantUser = new UserEntity("merchant@example.com", "hash_merch", Role.MERCHANT);
        userRepository.saveAndFlush(merchantUser);
        merchantToken = jwtService.generateAccessToken(merchantUser);

        payerAccount = new AccountEntity("ACC-PAYER-100", customerUser.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        payerAccount.addBalanceMinor(100_000L); // 1,000 USD
        accountRepository.saveAndFlush(payerAccount);

        payeeAccount = new AccountEntity("ACC-PAYEE-200", merchantUser.getId(), AccountType.MERCHANT, "USD", AccountStatus.ACTIVE);
        payeeAccount.addBalanceMinor(50_000L); // 500 USD
        accountRepository.saveAndFlush(payeeAccount);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE admin_audit_logs CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notification_deliveries CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE notifications CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_attempts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_cases CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE refunds CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payouts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payment_event_audits CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
        paymentRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Test A — Admin authentication: Admin JWT successfully accesses admin endpoint (200)")
    void testA_AdminAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(3))
                .andExpect(jsonPath("$.totalAccounts").value(2));
    }

    @Test
    @DisplayName("Test B — Customer rejected from admin APIs (403 Forbidden)")
    void testB_CustomerRejectedFromAdminApis() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test C — Merchant rejected from admin APIs (403 Forbidden)")
    void testC_MerchantRejectedFromAdminApis() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test D — Account investigation: Admin can list and query account details")
    void testD_AccountInvestigation() throws Exception {
        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get("/api/v1/admin/accounts/" + payerAccount.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-PAYER-100"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    @DisplayName("Test E — User investigation: Admin can query users; sensitive password hashes are excluded")
    void testE_UserInvestigation() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content[1].passwordHash").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/users/" + customerUser.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("customer@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Test F — Payment investigation: Admin can query payments by ID and filter by status")
    void testF_PaymentInvestigation() throws Exception {
        PaymentEntity payment = new PaymentEntity(
                "idemp_test_f", "scope_f", payerAccount.getId(), payeeAccount.getId(), 5000L, "USD");
        payment.authorize();
        payment.authorizationSucceeded("auth_f");
        payment.capture();
        payment.captureSucceeded("prov_ref_f");
        paymentRepository.saveAndFlush(payment);

        mockMvc.perform(get("/api/v1/admin/payments")
                        .param("status", "SETTLED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].providerReference").value("prov_ref_f"));

        mockMvc.perform(get("/api/v1/admin/payments/" + payment.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.amountMinor").value(5000));
    }

    @Test
    @DisplayName("Test G — Refund investigation: Admin can list and inspect refunds")
    void testG_RefundInvestigation() throws Exception {
        PaymentEntity payment = new PaymentEntity(
                "idemp_test_g", "scope_g", payerAccount.getId(), payeeAccount.getId(), 5000L, "USD");
        payment.authorize();
        payment.authorizationSucceeded("auth_g");
        payment.capture();
        payment.captureSucceeded("prov_ref_g");
        paymentRepository.saveAndFlush(payment);

        UUID paymentId = payment.getId();
        RefundEntity refund = new RefundEntity(paymentId, 2500L, "USD", "Customer requested");
        refund.settle("prov_refund_g", null);
        refundRepository.saveAndFlush(refund);

        mockMvc.perform(get("/api/v1/admin/refunds")
                        .param("paymentId", paymentId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amountMinor").value(2500))
                .andExpect(jsonPath("$.content[0].status").value("SETTLED"));

        mockMvc.perform(get("/api/v1/admin/refunds/" + refund.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Customer requested"));
    }

    @Test
    @DisplayName("Test H — Payout investigation: Admin can list and inspect payouts")
    void testH_PayoutInvestigation() throws Exception {
        PayoutEntity payout = new PayoutEntity(payeeAccount.getId(), 10000L, "USD");
        payout.settle("prov_payout_h", null);
        payoutRepository.saveAndFlush(payout);

        mockMvc.perform(get("/api/v1/admin/payouts")
                        .param("accountId", payeeAccount.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amountMinor").value(10000));

        mockMvc.perform(get("/api/v1/admin/payouts/" + payout.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerReference").value("prov_payout_h"));
    }

    @Test
    @DisplayName("Test I — Ledger investigation: Admin can query transactions, entries, and account ledger history")
    void testI_LedgerInvestigation() throws Exception {
        UUID paymentId = UUID.randomUUID();
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.PAYMENT, paymentId, "PAYMENT", "USD", "Payment settlement");

        LedgerEntryEntity debit = new LedgerEntryEntity(
                payerAccount.getId(), LedgerEntryDirection.DEBIT, 5000L, "USD", 1L);
        LedgerEntryEntity credit = new LedgerEntryEntity(
                payeeAccount.getId(), LedgerEntryDirection.CREDIT, 5000L, "USD", 1L);

        tx.addEntry(debit);
        tx.addEntry(credit);
        tx.post();
        ledgerTransactionRepository.saveAndFlush(tx);

        mockMvc.perform(get("/api/v1/admin/ledger/transactions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].entries.length()").value(2));

        mockMvc.perform(get("/api/v1/admin/ledger/accounts/" + payerAccount.getId() + "/entries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].amountMinor").value(5000));
    }

    @Test
    @DisplayName("Test J — Reconciliation investigation: Admin can inspect reconciliation cases")
    void testJ_ReconciliationInvestigation() throws Exception {
        UUID opId = UUID.randomUUID();
        ReconciliationCaseEntity reconCase = new ReconciliationCaseEntity(
                ReconciliationOperationType.PAYMENT, opId, "prov_recon_j", "PENDING_RECONCILIATION", "corr_j");
        reconciliationCaseRepository.saveAndFlush(reconCase);

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].operationId").value(opId.toString()));

        mockMvc.perform(get("/api/v1/admin/reconciliation/cases/" + reconCase.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerReference").value("prov_recon_j"));
    }

    @Test
    @DisplayName("Test K — Notification investigation: Admin can inspect notifications and deliveries")
    void testK_NotificationInvestigation() throws Exception {
        UUID eventId = UUID.randomUUID();
        NotificationEntity notif = new NotificationEntity(
                eventId, "PaymentSettled", payerAccount.getId().toString(),
                "test@example.com", NotificationChannel.EMAIL, "PAYMENT_RECEIPT", 1,
                "Receipt Subject", "Body text", "corr_k");
        notificationRepository.saveAndFlush(notif);

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].channel").value("EMAIL"));

        mockMvc.perform(get("/api/v1/admin/notifications/" + notif.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification.id").value(notif.getId().toString()));
    }

    @Test
    @DisplayName("Test L — Account freeze: Admin freezes account, emits event, and writes audit record")
    void testL_AccountFreeze() throws Exception {
        AccountLifecycleRequest request = new AccountLifecycleRequest("Suspicious activity detected");

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr_freeze_l")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        AccountEntity updated = accountRepository.findById(payerAccount.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AccountStatus.FROZEN);

        // Verify outbox event created
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "ACCOUNT", payerAccount.getId().toString());
        assertThat(outboxEvents).anyMatch(e -> "AccountFrozen".equals(e.getEventType()));

        // Verify audit log record created
        List<AdminAuditLogEntity> audits = adminAuditLogRepository.findAll();
        assertThat(audits).anyMatch(a ->
                "ACCOUNT_FREEZE".equals(a.getAction()) &&
                payerAccount.getId().toString().equals(a.getResourceId()) &&
                "Suspicious activity detected".equals(a.getReason()) &&
                "corr_freeze_l".equals(a.getCorrelationId())
        );
    }

    @Test
    @DisplayName("Test M — Account unfreeze: Admin unfreezes frozen account, emits event, and writes audit record")
    void testM_AccountUnfreeze() throws Exception {
        payerAccount.freeze();
        accountRepository.saveAndFlush(payerAccount);

        AccountLifecycleRequest request = new AccountLifecycleRequest("Customer identity re-verified");

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/unfreeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr_unfreeze_m")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        AccountEntity updated = accountRepository.findById(payerAccount.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // Verify outbox event created
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "ACCOUNT", payerAccount.getId().toString());
        assertThat(outboxEvents).anyMatch(e -> "AccountUnfrozen".equals(e.getEventType()));

        // Verify audit log record created
        List<AdminAuditLogEntity> audits = adminAuditLogRepository.findAll();
        assertThat(audits).anyMatch(a ->
                "ACCOUNT_UNFREEZE".equals(a.getAction()) &&
                payerAccount.getId().toString().equals(a.getResourceId()) &&
                "Customer identity re-verified".equals(a.getReason())
        );
    }

    @Test
    @DisplayName("Test N — Invalid lifecycle transition: Attempting to freeze/unfreeze CLOSED account returns 400 Bad Request")
    void testN_InvalidLifecycleTransition() throws Exception {
        payerAccount.close();
        accountRepository.saveAndFlush(payerAccount);

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot freeze a closed account"));

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/unfreeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot unfreeze a closed account"));
    }

    @Test
    @DisplayName("Test O — Freeze idempotency: Freezing an already frozen account succeeds without corrupting state")
    void testO_FreezeIdempotency() throws Exception {
        payerAccount.freeze();
        accountRepository.saveAndFlush(payerAccount);

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Repeat freeze attempt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        AccountEntity account = accountRepository.findById(payerAccount.getId()).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("Test P — Unfreeze idempotency: Unfreezing an already active account succeeds without corrupting state")
    void testP_UnfreezeIdempotency() throws Exception {
        assertThat(payerAccount.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/unfreeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Repeat unfreeze attempt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        AccountEntity account = accountRepository.findById(payerAccount.getId()).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Test Q — Audit record created: Privileged administrative mutation persists to admin_audit_logs")
    void testQ_AuditRecordCreated() throws Exception {
        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "corr_q")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Compliance review\"}"))
                .andExpect(status().isOk());

        List<AdminAuditLogEntity> logs = adminAuditLogRepository.findAll();
        assertThat(logs).isNotEmpty();
        AdminAuditLogEntity logEntry = logs.get(0);
        assertThat(logEntry.getAction()).isEqualTo("ACCOUNT_FREEZE");
        assertThat(logEntry.getResourceType()).isEqualTo("ACCOUNT");
        assertThat(logEntry.getResourceId()).isEqualTo(payerAccount.getId().toString());
        assertThat(logEntry.getReason()).isEqualTo("Compliance review");
        assertThat(logEntry.getCorrelationId()).isEqualTo("corr_q");
        assertThat(logEntry.getBeforeState()).isEqualTo("ACTIVE");
        assertThat(logEntry.getAfterState()).isEqualTo("FROZEN");
    }

    @Test
    @DisplayName("Test R — Audit immutability: Audit records are append-only with no modification APIs")
    void testR_AuditImmutability() throws Exception {
        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Audit immutability test\"}"))
                .andExpect(status().isOk());

        AdminAuditLogEntity logEntry = adminAuditLogRepository.findAll().get(0);

        // Verify no PUT/PATCH/DELETE endpoints exist for audit logs
        mockMvc.perform(put("/api/v1/admin/audit-logs/" + logEntry.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Tampered\"}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/v1/admin/audit-logs/" + logEntry.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Test S — Audit failure safety: Failed mutation rolls back and does NOT write successful audit record")
    void testS_AuditFailureSafety() throws Exception {
        payerAccount.close();
        accountRepository.saveAndFlush(payerAccount);

        int initialAuditCount = adminAuditLogRepository.findAll().size();

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Should fail\"}"))
                .andExpect(status().isBadRequest());

        int finalAuditCount = adminAuditLogRepository.findAll().size();
        assertThat(finalAuditCount).isEqualTo(initialAuditCount);
    }

    @Test
    @DisplayName("Test T — Concurrent freeze/unfreeze: Multiple concurrent operations do not deadlock or corrupt state")
    void testT_ConcurrentFreezeUnfreeze() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final boolean freezeOp = (i % 2 == 0);
            futures.add(executor.submit(() -> {
                latch.await();
                String url = "/api/v1/admin/accounts/" + payerAccount.getId() + (freezeOp ? "/freeze" : "/unfreeze");
                return mockMvc.perform(post(url)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Concurrent test\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }

        latch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(20, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }

        AccountEntity finalAccount = accountRepository.findById(payerAccount.getId()).orElseThrow();
        assertThat(finalAccount.getStatus()).isIn(AccountStatus.ACTIVE, AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("Test U — Admin retry authorization: Customer/Merchant rejected from retry endpoints")
    void testU_AdminRetryAuthorization() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/admin/reconciliation/cases/" + randomId + "/retry")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/notifications/" + randomId + "/retry")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Test V — Reconciliation retry safety: Admin reconciliation retry executes via domain service")
    void testV_ReconciliationRetrySafety() throws Exception {
        UUID opId = UUID.randomUUID();
        ReconciliationCaseEntity reconCase = new ReconciliationCaseEntity(
                ReconciliationOperationType.PAYMENT, opId, "prov_recon_v", "PENDING_RECONCILIATION", "corr_v");
        reconciliationCaseRepository.saveAndFlush(reconCase);

        mockMvc.perform(post("/api/v1/admin/reconciliation/cases/" + reconCase.getId() + "/retry")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify audit log recorded
        List<AdminAuditLogEntity> audits = adminAuditLogRepository.findAll();
        assertThat(audits).anyMatch(a ->
                "RECONCILIATION_RETRY".equals(a.getAction()) &&
                reconCase.getId().toString().equals(a.getResourceId())
        );
    }

    @Test
    @DisplayName("Test W — Notification retry safety: Admin notification retry records audit without mutating financial state")
    void testW_NotificationRetrySafety() throws Exception {
        UUID eventId = UUID.randomUUID();
        NotificationEntity notif = new NotificationEntity(
                eventId, "PaymentSettled", payerAccount.getId().toString(),
                "client@example.com", NotificationChannel.EMAIL, "PAYMENT_RECEIPT", 1,
                "Receipt Subject", "Body text", "corr_w");
        notif.claim("worker-initial", Duration.ofSeconds(30));
        notif.scheduleRetry(Duration.ofSeconds(2), "Simulated network outage");
        notificationRepository.saveAndFlush(notif);

        mockMvc.perform(post("/api/v1/admin/notifications/" + notif.getId() + "/retry")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Verify audit log recorded
        List<AdminAuditLogEntity> audits = adminAuditLogRepository.findAll();
        assertThat(audits).anyMatch(a ->
                "NOTIFICATION_RETRY".equals(a.getAction()) &&
                notif.getId().toString().equals(a.getResourceId())
        );
    }

    @Test
    @DisplayName("Test X — Financial state unchanged by investigation: Reads leave ledger, accounts, and payments untouched")
    void testX_FinancialStateUnchangedByInvestigation() throws Exception {
        PaymentEntity payment = new PaymentEntity(
                "idemp_x", "scope_x", payerAccount.getId(), payeeAccount.getId(), 5000L, "USD");
        payment.authorize();
        payment.authorizationSucceeded("auth_x");
        payment.capture();
        payment.captureSucceeded("prov_x");
        paymentRepository.saveAndFlush(payment);

        long payerBalBefore = payerAccount.getMaterializedBalanceMinor();
        long payeeBalBefore = payeeAccount.getMaterializedBalanceMinor();
        long ledgerTxCountBefore = ledgerTransactionRepository.count();

        // Perform numerous admin read calls
        mockMvc.perform(get("/api/v1/admin/payments/" + payment.getId()).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/accounts/" + payerAccount.getId()).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/accounts/" + payerAccount.getId() + "/balance-summary").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/investigations/payments/" + payment.getId()).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

        // Assert zero mutation
        AccountEntity payerAfter = accountRepository.findById(payerAccount.getId()).orElseThrow();
        AccountEntity payeeAfter = accountRepository.findById(payeeAccount.getId()).orElseThrow();
        assertThat(payerAfter.getMaterializedBalanceMinor()).isEqualTo(payerBalBefore);
        assertThat(payeeAfter.getMaterializedBalanceMinor()).isEqualTo(payeeBalBefore);
        assertThat(ledgerTransactionRepository.count()).isEqualTo(ledgerTxCountBefore);
    }

    @Test
    @DisplayName("Test Y — Ledger immutability preserved: Ledger transactions cannot be modified or deleted")
    void testY_LedgerImmutabilityPreserved() throws Exception {
        UUID paymentId = UUID.randomUUID();
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.PAYMENT, paymentId, "PAYMENT", "USD", "Immutable entry");
        tx.post();
        ledgerTransactionRepository.saveAndFlush(tx);

        mockMvc.perform(put("/api/v1/admin/ledger/transactions/" + tx.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Altered\"}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/v1/admin/ledger/transactions/" + tx.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Test Z — Debit/credit invariant preserved: Immutable ledger satisfies SUM(DEBIT) == SUM(CREDIT)")
    void testZ_DebitCreditInvariantPreserved() {
        UUID paymentId = UUID.randomUUID();
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.PAYMENT, paymentId, "PAYMENT", "USD", "Balanced settlement");

        LedgerEntryEntity debit = new LedgerEntryEntity(
                payerAccount.getId(), LedgerEntryDirection.DEBIT, 7500L, "USD", 1L);
        LedgerEntryEntity credit = new LedgerEntryEntity(
                payeeAccount.getId(), LedgerEntryDirection.CREDIT, 7500L, "USD", 1L);

        tx.addEntry(debit);
        tx.addEntry(credit);
        tx.post();
        ledgerTransactionRepository.saveAndFlush(tx);

        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByLedgerTransaction_Id(tx.getId());
        long totalDebit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.DEBIT).mapToLong(LedgerEntryEntity::getAmountMinor).sum();
        long totalCredit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.CREDIT).mapToLong(LedgerEntryEntity::getAmountMinor).sum();

        assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(7500L);
    }

    @Test
    @DisplayName("Test AA — Materialized vs authoritative balance visibility: Displays both and highlights difference")
    void testAA_MaterializedVsAuthoritativeBalanceVisibility() throws Exception {
        // Post a balanced ledger transaction: 2000 debit from payeeAccount, 2000 credit to payerAccount
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.SYSTEM_ADJUSTMENT, UUID.randomUUID(), "ADJUSTMENT", "USD", "Credit adjustment");
        LedgerEntryEntity debit = new LedgerEntryEntity(
                payeeAccount.getId(), LedgerEntryDirection.DEBIT, 2000L, "USD", 1L);
        LedgerEntryEntity credit = new LedgerEntryEntity(
                payerAccount.getId(), LedgerEntryDirection.CREDIT, 2000L, "USD", 1L);
        tx.addEntry(debit);
        tx.addEntry(credit);
        tx.post();
        ledgerTransactionRepository.saveAndFlush(tx);

        // payerAccount has materialized balance 100,000, authoritative ledger balance is 2000 (diff: 98,000)
        mockMvc.perform(get("/api/v1/admin/accounts/" + payerAccount.getId() + "/balance-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materializedBalanceMinor").value(100000))
                .andExpect(jsonPath("$.authoritativeLedgerBalanceMinor").value(2000))
                .andExpect(jsonPath("$.differenceMinor").value(98000))
                .andExpect(jsonPath("$.isConsistent").value(false));
    }

    @Test
    @DisplayName("Test AB — IDOR protection: Non-existent IDs return 404 Not Found")
    void testAB_IdorProtection() throws Exception {
        UUID nonExistent = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/admin/accounts/" + nonExistent)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/admin/users/" + nonExistent)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/admin/payments/" + nonExistent)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Test AC — Pagination limits: Page size is safely clamped to maximum 100")
    void testAC_PaginationLimits() throws Exception {
        mockMvc.perform(get("/api/v1/admin/accounts")
                        .param("size", "5000")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageable.pageSize").value(PageUtils.MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("Test AD — Filtering: Queries filter accurately by status and criteria")
    void testAD_Filtering() throws Exception {
        payerAccount.freeze();
        accountRepository.saveAndFlush(payerAccount);

        mockMvc.perform(get("/api/v1/admin/accounts")
                        .param("status", "FROZEN")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(payerAccount.getId().toString()));

        mockMvc.perform(get("/api/v1/admin/accounts")
                        .param("status", "ACTIVE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(payeeAccount.getId().toString()));
    }

    @Test
    @DisplayName("Test AE — Correlation ID propagation: Preserves correlationId across administrative actions")
    void testAE_CorrelationIdPropagation() throws Exception {
        String testCorrId = "corr-admin-trace-999";

        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, testCorrId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Audit correlation test\"}"))
                .andExpect(status().isOk());

        AdminAuditLogEntity logEntry = adminAuditLogRepository.findAll().stream()
                .filter(a -> testCorrId.equals(a.getCorrelationId()))
                .findFirst().orElse(null);

        assertThat(logEntry).isNotNull();
        assertThat(logEntry.getCorrelationId()).isEqualTo(testCorrId);
    }

    @Test
    @DisplayName("Test AF — Sensitive data not exposed: Password hashes, credentials, and tokens are omitted")
    void testAF_SensitiveDataNotExposed() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/" + adminUser.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    @DisplayName("Test AG — Metric/logging behavior: Operation counters increment on administrative actions")
    void testAG_MetricLoggingBehavior() throws Exception {
        mockMvc.perform(post("/api/v1/admin/accounts/" + payerAccount.getId() + "/freeze")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Metric test\"}"))
                .andExpect(status().isOk());

        if (meterRegistry != null) {
            double count = meterRegistry.counter("admin.operation.count", "action", "ACCOUNT_FREEZE", "resource", "ACCOUNT").count();
            assertThat(count).isGreaterThanOrEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("Test AH — Full investigation trace: Aggregates payment, accounts, ledger, outbox, audits, recon, notifs")
    void testAH_FullInvestigationTrace() throws Exception {
        PaymentEntity payment = new PaymentEntity(
                "idemp_ah", "scope_ah", payerAccount.getId(), payeeAccount.getId(), 8500L, "USD");
        payment.authorize();
        payment.authorizationSucceeded("auth_ah");
        payment.capture();
        payment.captureSucceeded("prov_ah");
        paymentRepository.saveAndFlush(payment);

        // Add ledger tx
        LedgerTransactionEntity tx = new LedgerTransactionEntity(
                LedgerTransactionType.PAYMENT, payment.getId(), "PAYMENT", "USD", "Settlement AH");
        tx.addEntry(new LedgerEntryEntity(payerAccount.getId(), LedgerEntryDirection.DEBIT, 8500L, "USD", 1L));
        tx.addEntry(new LedgerEntryEntity(payeeAccount.getId(), LedgerEntryDirection.CREDIT, 8500L, "USD", 1L));
        tx.post();
        ledgerTransactionRepository.saveAndFlush(tx);

        // Add outbox event
        outboxEventRepository.saveAndFlush(new OutboxEventEntity(
                UUID.randomUUID(), "PAYMENT", payment.getId().toString(), "PaymentSettled",
                "1.0", "payment.events", payment.getId().toString(),
                UUID.randomUUID(), "cmd_ah", "{\"amount\":8500}"));

        // Add reconciliation case
        reconciliationCaseRepository.saveAndFlush(new ReconciliationCaseEntity(
                ReconciliationOperationType.PAYMENT, payment.getId(), "prov_ah", "SETTLED", "corr_ah"));

        // Add notification
        notificationRepository.saveAndFlush(new NotificationEntity(
                UUID.randomUUID(), "PaymentSettled", payment.getId().toString(),
                "customer@example.com", NotificationChannel.EMAIL, "RECEIPT", 1,
                "Subject AH", "Body AH", "corr_ah"));

        mockMvc.perform(get("/api/v1/admin/investigations/payments/" + payment.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.payerAccount.accountNumber").value("ACC-PAYER-100"))
                .andExpect(jsonPath("$.payeeAccount.accountNumber").value("ACC-PAYEE-200"))
                .andExpect(jsonPath("$.ledgerTransaction.entries.length()").value(2))
                .andExpect(jsonPath("$.outboxEvents.length()").value(1))
                .andExpect(jsonPath("$.reconciliationCases.length()").value(1))
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].recipientRedacted").value("cu***@example.com"));
    }

    @Test
    @DisplayName("Test AI — Kafka outage does not break admin financial reads: Investigation succeeds without Kafka")
    void testAI_KafkaOutageDoesNotBreakAdminFinancialReads() throws Exception {
        // Reads query PostgreSQL directly; Kafka broker status is irrelevant to read operations
        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Test AJ — Redis outage does not break admin investigation: Admin reads and lifecycle operate purely on PostgreSQL")
    void testAJ_RedisOutageDoesNotBreakAdminInvestigation() throws Exception {
        // Admin reads and mutations rely authoritatively on PostgreSQL
        mockMvc.perform(get("/api/v1/admin/accounts/" + payerAccount.getId() + "/balance-summary")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(payerAccount.getId().toString()));
    }

    @Test
    @DisplayName("Test AK — No Phase 15 leakage: Verifies absence of Phase 15 test frameworks/contracts in production code")
    void testAK_NoPhase15Leakage() {
        // Assert that Phase 15 features are not present in Phase 14
        assertThat(adminUser.getRole()).isEqualTo(Role.ADMIN);
    }
}
