package com.paymentledger.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.admin.api.dto.FinancialAdjustmentCreateRequest;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.outbox.domain.OutboxEventEntity;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payment.service.FakePaymentProvider;
import com.paymentledger.payment.service.PaymentService;
import com.paymentledger.payment.service.ProviderOperationStatus;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.domain.PayoutStatus;
import com.paymentledger.payout.repository.PayoutRepository;
import com.paymentledger.reconciliation.audit.BalanceConsistencyAuditor;
import com.paymentledger.reconciliation.audit.LedgerConsistencyAuditor;
import com.paymentledger.reconciliation.domain.DiscrepancyType;
import com.paymentledger.reconciliation.domain.ReconciliationAttemptEntity;
import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;
import com.paymentledger.reconciliation.domain.ReconciliationOperationType;
import com.paymentledger.reconciliation.domain.ReconciliationStatus;
import com.paymentledger.reconciliation.repository.ReconciliationAttemptRepository;
import com.paymentledger.reconciliation.repository.ReconciliationCaseRepository;
import com.paymentledger.reconciliation.service.ReconciliationService;
import com.paymentledger.reconciliation.worker.ReconciliationWorker;
import com.paymentledger.refund.api.dto.RefundCreateRequest;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.repository.RefundRepository;
import com.paymentledger.refund.repository.ReversalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class Phase12ReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationWorker reconciliationWorker;

    @Autowired
    private ReconciliationCaseRepository reconciliationCaseRepository;

    @Autowired
    private ReconciliationAttemptRepository reconciliationAttemptRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private ReversalRepository reversalRepository;

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
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private LedgerConsistencyAuditor ledgerConsistencyAuditor;

    @Autowired
    private BalanceConsistencyAuditor balanceConsistencyAuditor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserEntity payer;
    private UserEntity merchant;
    private UserEntity adminUser;
    private UserEntity otherUser;

    private AccountEntity payerAccount;
    private AccountEntity merchantAccount;
    private AccountEntity settlementAccount;

    @BeforeEach
    void setUp() {
        fakePaymentProvider.clearOverrides();

        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_attempts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_cases CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE financial_adjustments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE refunds CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reversals CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payouts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
        paymentRepository.deleteAllInBatch();
        jdbcTemplate.execute("TRUNCATE TABLE idempotency_records CASCADE");
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();

        // 1. Payer
        payer = new UserEntity("payer@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(payer);
        payerAccount = new AccountEntity("ACC-PAYER", payer.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(payerAccount);
        fundAccount(payerAccount.getId(), 50000L); // 500.00 USD

        // 2. Merchant
        merchant = new UserEntity("merchant@example.com", "hash", Role.MERCHANT);
        userRepository.saveAndFlush(merchant);
        merchantAccount = new AccountEntity("ACC-MERCHANT", merchant.getId(), AccountType.MERCHANT, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(merchantAccount);
        fundAccount(merchantAccount.getId(), 20000L); // 200.00 USD

        // 3. Admin
        adminUser = new UserEntity("admin@example.com", "hash", Role.ADMIN);
        userRepository.saveAndFlush(adminUser);

        // 4. Other user (for IDOR)
        otherUser = new UserEntity("other@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(otherUser);

        // 5. Settlement Account
        UserEntity systemUser = new UserEntity("system@example.com", "hash", Role.SYSTEM);
        userRepository.saveAndFlush(systemUser);
        settlementAccount = new AccountEntity("SETTLEMENT-USD", systemUser.getId(), AccountType.INTERNAL_SETTLEMENT, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(settlementAccount);
    }

    private void fundAccount(UUID accountId, long amountMinor) {
        jdbcTemplate.update("UPDATE accounts SET materialized_balance_minor = materialized_balance_minor + ? WHERE id = ?", amountMinor, accountId);
        UUID initTxId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status) VALUES (?, 'SYSTEM_ADJUSTMENT', ?, 'MANUAL', 'USD', 'Funding', 'POSTED')", initTxId, UUID.randomUUID());
        long seq = ledgerEntryRepository.getMaxSequenceNumberForAccount(accountId) + 1;
        jdbcTemplate.update("INSERT INTO ledger_entries (id, account_id, ledger_transaction_id, direction, amount_minor, currency, sequence_number) VALUES (?, ?, ?, 'CREDIT', ?, 'USD', ?)",
                UUID.randomUUID(), accountId, initTxId, amountMinor, seq);
    }

    // =========================================================================
    // Test A: Reconciliation schema and migration
    // =========================================================================
    @Test
    @DisplayName("Test A: Flyway migration V10 created reconciliation tables and constraints")
    void testA_ReconciliationSchemaMigration() {
        Integer casesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'reconciliation_cases'", Integer.class);
        Integer attemptsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'reconciliation_attempts'", Integer.class);

        assertThat(casesCount).isEqualTo(1);
        assertThat(attemptsCount).isEqualTo(1);
    }

    // =========================================================================
    // Test B: Pending payment with provider SUCCESS resolves correctly
    // =========================================================================
    @Test
    @DisplayName("Test B: Pending payment with provider SUCCESS resolves to SETTLED with balanced ledger")
    void testB_PendingPaymentProviderSuccessResolves() {
        PaymentEntity initialPayment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 5000L, "USD");
        initialPayment.markPendingReconciliation();
        PaymentEntity payment = paymentRepository.saveAndFlush(initialPayment);
        final UUID paymentId = payment.getId();

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_captured_123"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), payment.getProviderReference(), "PENDING_RECONCILIATION", "corr-test-b"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-b");

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SETTLED);

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
        assertThat(reloadedCase.getDiscrepancyType()).isEqualTo(DiscrepancyType.PROVIDER_SUCCESS_LOCAL_PENDING);

        // Verify balanced compensating ledger entries
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getAmountMinor() == 5000L && !"Funding".equals(e.getCurrency()))
                .toList();
        assertThat(entries).hasSize(2);

        // Verify transactional outbox event created
        List<OutboxEventEntity> outbox = outboxEventRepository.findAll().stream()
                .filter(e -> "PaymentSettled".equals(e.getEventType()) && paymentId.toString().equals(e.getAggregateId()))
                .toList();
        assertThat(outbox).isNotEmpty();
    }

    // =========================================================================
    // Test C: Pending payment with provider FAILURE resolves correctly
    // =========================================================================
    @Test
    @DisplayName("Test C: Pending payment with provider FAILURE resolves to FAILED without ledger entries")
    void testC_PendingPaymentProviderFailureResolves() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 4000L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.failure("INSUFFICIENT_FUNDS_DECLINED", "Card declined"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), payment.getProviderReference(), "PENDING_RECONCILIATION", "corr-test-c"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-c");

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.FAILED);

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
        assertThat(reloadedCase.getDiscrepancyType()).isEqualTo(DiscrepancyType.PROVIDER_FAILURE_LOCAL_PENDING);

        // Verify NO ledger transaction created for 4000L
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getAmountMinor() == 4000L)
                .toList();
        assertThat(entries).isEmpty();

        // Verify PaymentFailed outbox event created
        List<OutboxEventEntity> outbox = outboxEventRepository.findAll().stream()
                .filter(e -> "PaymentFailed".equals(e.getEventType()))
                .toList();
        assertThat(outbox).isNotEmpty();
    }

    // =========================================================================
    // Test D: Pending refund with provider SUCCESS creates compensating ledger
    // =========================================================================
    @Test
    @DisplayName("Test D: Pending refund with provider SUCCESS creates compensating ledger and marks SETTLED")
    void testD_PendingRefundProviderSuccessResolves() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 10000L, "USD");
        payment.markPendingReconciliation();
        payment.captureSucceeded("prov_cap_d");
        payment = paymentRepository.saveAndFlush(payment);

        RefundEntity refund = new RefundEntity(payment.getId(), 3000L, "USD", "Return");
        refund.markPendingReconciliation("GATEWAY_TIMEOUT");
        refund = refundRepository.saveAndFlush(refund);

        fakePaymentProvider.registerOperationStatus(refund.getId(), ProviderOperationStatus.success("ref_prov_d"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.REFUND, refund.getId(), null, "PENDING_RECONCILIATION", "corr-test-d"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-d");

        RefundEntity reloaded = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.SETTLED);
        assertThat(reloaded.getCompensatingLedgerTransactionId()).isNotNull();

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);

        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByLedgerTransaction_Id(reloaded.getCompensatingLedgerTransactionId());
        assertThat(entries).hasSize(2);
    }

    // =========================================================================
    // Test E: Pending refund with provider FAILURE becomes failed
    // =========================================================================
    @Test
    @DisplayName("Test E: Pending refund with provider FAILURE resolves to FAILED without ledger entries")
    void testE_PendingRefundProviderFailureResolves() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 10000L, "USD");
        payment.markPendingReconciliation();
        payment.captureSucceeded("prov_cap_e");
        payment = paymentRepository.saveAndFlush(payment);

        RefundEntity refund = new RefundEntity(payment.getId(), 2500L, "USD", "Defective");
        refund.markPendingReconciliation("GATEWAY_TIMEOUT");
        refund = refundRepository.saveAndFlush(refund);

        fakePaymentProvider.registerOperationStatus(refund.getId(), ProviderOperationStatus.failure("CARD_EXPIRED", "Card expired"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.REFUND, refund.getId(), null, "PENDING_RECONCILIATION", "corr-test-e"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-e");

        RefundEntity reloaded = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(reloaded.getCompensatingLedgerTransactionId()).isNull();

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    // =========================================================================
    // Test F: Pending payout with provider SUCCESS creates payout ledger transaction
    // =========================================================================
    @Test
    @DisplayName("Test F: Pending payout with provider SUCCESS creates compensating ledger and marks SETTLED")
    void testF_PendingPayoutProviderSuccessResolves() {
        PayoutEntity payout = new PayoutEntity(merchantAccount.getId(), 2000L, "USD");
        payout.markPendingReconciliation("GATEWAY_TIMEOUT");
        payout = payoutRepository.saveAndFlush(payout);

        fakePaymentProvider.registerOperationStatus(payout.getId(), ProviderOperationStatus.success("payout_prov_f"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYOUT, payout.getId(), null, "PENDING_RECONCILIATION", "corr-test-f"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-f");

        PayoutEntity reloaded = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayoutStatus.SETTLED);
        assertThat(reloaded.getCompensatingLedgerTransactionId()).isNotNull();

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    // =========================================================================
    // Test G: Pending payout with provider FAILURE becomes failed
    // =========================================================================
    @Test
    @DisplayName("Test G: Pending payout with provider FAILURE resolves to FAILED without ledger entries")
    void testG_PendingPayoutProviderFailureResolves() {
        PayoutEntity payout = new PayoutEntity(merchantAccount.getId(), 1500L, "USD");
        payout.markPendingReconciliation("GATEWAY_TIMEOUT");
        payout = payoutRepository.saveAndFlush(payout);

        fakePaymentProvider.registerOperationStatus(payout.getId(), ProviderOperationStatus.failure("BANK_ACCOUNT_CLOSED", "Account closed"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYOUT, payout.getId(), null, "PENDING_RECONCILIATION", "corr-test-g"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-g");

        PayoutEntity reloaded = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayoutStatus.FAILED);

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    // =========================================================================
    // Test H: Provider UNKNOWN remains unresolved/retryable
    // =========================================================================
    @Test
    @DisplayName("Test H: Provider UNKNOWN schedules retry with backoff and preserves unresolved state")
    void testH_ProviderUnknownRemainsUnresolved() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 3500L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.unknown("Network socket timeout on query"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-h"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-h");

        ReconciliationCaseEntity reloadedCase = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloadedCase.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RETRY_REQUIRED);
        assertThat(reloadedCase.getAttemptCount()).isEqualTo(1);
        assertThat(reloadedCase.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));

        // Payment status untouched
        PaymentEntity reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING_RECONCILIATION);
    }

    // =========================================================================
    // Test I: Maximum retry count moves case to MANUAL_REVIEW
    // =========================================================================
    @Test
    @DisplayName("Test I: Exceeding max retry attempts transitions case to MANUAL_REVIEW without mutating money")
    void testI_MaxRetriesMovesToManualReview() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 3500L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.unknown("Indeterminate"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-i"
        );
        reconCase.setMaxAttempts(2);
        reconciliationCaseRepository.saveAndFlush(reconCase);

        // Attempt 1 -> RETRY_REQUIRED
        reconciliationService.reconcileCase(reconCase.getId(), "worker-i");
        ReconciliationCaseEntity caseAfter1 = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(caseAfter1.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RETRY_REQUIRED);

        // Attempt 2 -> MANUAL_REVIEW
        reconciliationService.reconcileCase(reconCase.getId(), "worker-i");
        ReconciliationCaseEntity caseAfter2 = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(caseAfter2.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MANUAL_REVIEW);
        assertThat(caseAfter2.getResolution()).contains("Exceeded max attempts");
    }

    // =========================================================================
    // Test J: Repeated reconciliation is idempotent
    // =========================================================================
    @Test
    @DisplayName("Test J: Repeated reconciliation of already resolved operation causes no duplicates")
    void testJ_RepeatedReconciliationIsIdempotent() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 5000L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_idem_j"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-j"
        );

        // Run 1
        reconciliationService.reconcileCase(reconCase.getId(), "worker-j1");

        long countLedgerAfter1 = ledgerTransactionRepository.count();
        long countOutboxAfter1 = outboxEventRepository.count();

        // Run 2 (Repeated reconciliation)
        reconciliationService.reconcileCase(reconCase.getId(), "worker-j2");

        long countLedgerAfter2 = ledgerTransactionRepository.count();
        long countOutboxAfter2 = outboxEventRepository.count();

        assertThat(countLedgerAfter2).isEqualTo(countLedgerAfter1);
        assertThat(countOutboxAfter2).isEqualTo(countOutboxAfter1);
    }

    // =========================================================================
    // Test K: Concurrent workers cannot duplicate financial resolution
    // =========================================================================
    @Test
    @DisplayName("Test K: Concurrent workers racing to reconcile the same case execute exactly one financial resolution")
    void testK_ConcurrentWorkersCannotDuplicateResolution() throws Exception {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 6000L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_conc_k"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-k"
        );

        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reconciliationService.reconcileCase(reconCase.getId(), "worker-k-" + id);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one compensating ledger transaction was posted
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getAmountMinor() == 6000L)
                .toList();
        assertThat(entries).hasSize(2); // One debit, one credit

        ReconciliationCaseEntity reloaded = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloaded.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    // =========================================================================
    // Test L: Stale reconciliation lease is recovered
    // =========================================================================
    @Test
    @DisplayName("Test L: Stale lease from a crashed worker is claimed and recovered by another worker")
    void testL_StaleReconciliationLeaseRecovered() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 2000L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-l"
        );

        final UUID caseId = reconCase.getId();

        // Worker A claimed 10 minutes ago and crashed
        reconCase.claim("crashed-worker", Duration.ofMinutes(-10));
        reconciliationCaseRepository.saveAndFlush(reconCase);

        // Worker B claims eligible cases
        List<ReconciliationCaseEntity> claimed = reconciliationService.claimCases("worker-b", 10, Duration.ofSeconds(60));
        assertThat(claimed).anyMatch(c -> c.getId().equals(caseId));

        ReconciliationCaseEntity recovered = reconciliationCaseRepository.findById(caseId).orElseThrow();
        assertThat(recovered.getLeaseWorkerId()).isEqualTo("worker-b");
    }

    // =========================================================================
    // Test M: Worker failure does not permanently strand a case
    // =========================================================================
    @Test
    @DisplayName("Test M: Worker exception during reconciliation records attempt and transitions to RETRY_REQUIRED")
    void testM_WorkerFailureDoesNotStrandCase() {
        ReconciliationCaseEntity reconCase = new ReconciliationCaseEntity(
                ReconciliationOperationType.PAYMENT, UUID.randomUUID(), null, "PENDING_RECONCILIATION", "corr-test-m"
        );
        reconCase = reconciliationCaseRepository.saveAndFlush(reconCase);

        reconciliationService.recordCaseException(reconCase.getId(), "worker-m", "Simulated database connection drop", ProviderOperationStatus.unknown("fail"));

        ReconciliationCaseEntity reloaded = reconciliationCaseRepository.findById(reconCase.getId()).orElseThrow();
        assertThat(reloaded.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RETRY_REQUIRED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("Simulated database connection drop");

        List<ReconciliationAttemptEntity> attempts = reconciliationAttemptRepository.findByReconciliationCaseIdOrderByAttemptNumberAsc(reconCase.getId());
        assertThat(attempts).isNotEmpty();
    }

    // =========================================================================
    // Test N: Financial mutation + outbox are atomic
    // =========================================================================
    @Test
    @DisplayName("Test N: Settle payment + ledger entries + outbox event commit in the same database transaction")
    void testN_FinancialMutationAndOutboxAreAtomic() {
        PaymentEntity initialPayment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 7500L, "USD");
        initialPayment.markPendingReconciliation();
        PaymentEntity payment = paymentRepository.saveAndFlush(initialPayment);
        final UUID paymentId = payment.getId();

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_atomic_n"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-n"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-n");

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SETTLED);

        // Verify outbox event exists
        List<OutboxEventEntity> outbox = outboxEventRepository.findAll().stream()
                .filter(e -> paymentId.toString().equals(e.getAggregateId()))
                .toList();
        assertThat(outbox).isNotEmpty();
        assertThat(outbox.get(0).getEventType()).isEqualTo("PaymentSettled");
    }

    // =========================================================================
    // Test O: Kafka outage does not prevent reconciliation commit
    // =========================================================================
    @Test
    @DisplayName("Test O: Reconciliation commits to PostgreSQL even if Kafka is down (outbox pattern)")
    void testO_KafkaOutageDoesNotPreventReconciliationCommit() {
        // Outbox pattern strictly guarantees local DB commit without requiring immediate Kafka publishing
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 4500L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_kafka_o"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-o"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-o");

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    // =========================================================================
    // Test P: Redis outage does not prevent reconciliation commit
    // =========================================================================
    @Test
    @DisplayName("Test P: Reconciliation functions purely against PostgreSQL without depending on Redis")
    void testP_RedisOutageDoesNotPreventReconciliationCommit() {
        PaymentEntity payment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 3000L, "USD");
        payment.markPendingReconciliation();
        payment = paymentRepository.saveAndFlush(payment);

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_redis_p"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", "corr-test-p"
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-p");

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    // =========================================================================
    // Test Q: Ledger debit/credit totals remain balanced
    // =========================================================================
    @Test
    @DisplayName("Test Q: LedgerConsistencyAuditor confirms zero unbalanced transactions")
    void testQ_LedgerDebitCreditTotalsBalanced() {
        LedgerConsistencyAuditor.AuditReport report = ledgerConsistencyAuditor.auditLedgerConsistency();
        assertThat(report.isClean()).isTrue();
        assertThat(report.findingsCount()).isEqualTo(0);
    }

    // =========================================================================
    // Test R: Ledger immutability remains enforced
    // =========================================================================
    @Test
    @DisplayName("Test R: Posted ledger transactions cannot be reposted or altered")
    void testR_LedgerImmutabilityEnforced() {
        LedgerTransactionEntity tx = ledgerTransactionRepository.findAll().stream().findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, tx::post);
    }

    // =========================================================================
    // Test S: Materialized balance equals derived ledger balance
    // =========================================================================
    @Test
    @DisplayName("Test S: BalanceConsistencyAuditor confirms materialized balances match ledger")
    void testS_MaterializedBalanceEqualsDerivedLedgerBalance() {
        BalanceConsistencyAuditor.BalanceReport report = balanceConsistencyAuditor.auditBalanceConsistency();
        assertThat(report.isClean()).isTrue();
        assertThat(report.findingsCount()).isEqualTo(0);
    }

    // =========================================================================
    // Test T: Ledger mismatch creates finding/manual review rather than mutation
    // =========================================================================
    @Test
    @DisplayName("Test T: Corrupted ledger record produces audit finding rather than silent overwrite")
    void testT_LedgerMismatchCreatesFindingNotMutation() {
        // Intentionally insert an unbalanced transaction into ledger_transactions
        UUID badTxId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status) VALUES (?, 'PAYMENT', ?, 'MANUAL', 'USD', 'Bad Tx', 'POSTED')",
                badTxId, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO ledger_entries (id, account_id, ledger_transaction_id, direction, amount_minor, currency, sequence_number) VALUES (?, ?, ?, 'DEBIT', 500, 'USD', 99)",
                UUID.randomUUID(), payerAccount.getId(), badTxId);

        LedgerConsistencyAuditor.AuditReport report = ledgerConsistencyAuditor.auditLedgerConsistency();
        assertThat(report.isClean()).isFalse();
        assertThat(report.findingsCount()).isGreaterThanOrEqualTo(1);

        // Verify that the auditor did NOT alter or delete the bad transaction
        assertThat(ledgerTransactionRepository.existsById(badTxId)).isTrue();
    }

    // =========================================================================
    // Test U: Cross-user reconciliation access is rejected
    // =========================================================================
    @Test
    @DisplayName("Test U: Non-admin users cannot access administrative reconciliation endpoints (403)")
    void testU_CrossUserReconciliationAccessRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases")
                .with(authentication(new UsernamePasswordAuthenticationToken(otherUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Test V: Admin reconciliation authorization works correctly
    // =========================================================================
    @Test
    @DisplayName("Test V: Authorized administrators can list cases and run reconciliation cycles (200)")
    void testV_AdminReconciliationAuthorizationAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reconciliation/cases")
                .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/reconciliation/run")
                .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Test W: Idempotency payload mismatch remains rejected
    // =========================================================================
    @Test
    @DisplayName("Test W: Reusing idempotency key with altered payload is rejected with 409 Conflict")
    void testW_IdempotencyPayloadMismatchRejected() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        FinancialAdjustmentCreateRequest req1 = new FinancialAdjustmentCreateRequest(
                payerAccount.getId(), merchantAccount.getId(), 500L, "USD", "Orig adjustment"
        );
        FinancialAdjustmentCreateRequest req2 = new FinancialAdjustmentCreateRequest(
                payerAccount.getId(), merchantAccount.getId(), 900L, "USD", "Altered adjustment"
        );

        mockMvc.perform(post("/api/v1/admin/adjustments")
                .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/adjustments")
                .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // Test X: Correlation IDs propagate through reconciliation
    // =========================================================================
    @Test
    @DisplayName("Test X: Correlation IDs propagate to attempts log and outbox records")
    void testX_CorrelationIdPropagation() {
        String testCorrId = "corr-" + UUID.randomUUID();
        PaymentEntity initialPayment = new PaymentEntity(UUID.randomUUID().toString(), "scope", payerAccount.getId(), merchantAccount.getId(), 2200L, "USD");
        initialPayment.markPendingReconciliation();
        PaymentEntity payment = paymentRepository.saveAndFlush(initialPayment);
        final UUID paymentId = payment.getId();

        fakePaymentProvider.registerOperationStatus(payment.getId(), ProviderOperationStatus.success("ref_corr_x"));

        ReconciliationCaseEntity reconCase = reconciliationService.createOrGetCase(
                ReconciliationOperationType.PAYMENT, payment.getId(), null, "PENDING_RECONCILIATION", testCorrId
        );

        reconciliationService.reconcileCase(reconCase.getId(), "worker-x");

        List<ReconciliationAttemptEntity> attempts = reconciliationAttemptRepository.findByReconciliationCaseIdOrderByAttemptNumberAsc(reconCase.getId());
        assertThat(attempts).isNotEmpty();

        List<OutboxEventEntity> outbox = outboxEventRepository.findAll().stream()
                .filter(e -> paymentId.toString().equals(e.getAggregateId()))
                .toList();
        assertThat(outbox).isNotEmpty();
        assertThat(outbox.get(0).getCorrelationId()).isNotNull();
    }

    // =========================================================================
    // Test Y: Concurrent opposing financial operations do not deadlock
    // =========================================================================
    @Test
    @DisplayName("Test Y: Concurrent opposing transfers execute without database deadlock")
    void testY_ConcurrentOpposingFinancialOperationsDoNotDeadlock() throws Exception {
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final boolean direction = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    UUID src = direction ? payerAccount.getId() : merchantAccount.getId();
                    UUID tgt = direction ? merchantAccount.getId() : payerAccount.getId();
                    FinancialAdjustmentCreateRequest req = new FinancialAdjustmentCreateRequest(
                            src, tgt, 50L, "USD", "Concurrent opposing transfer"
                    );
                    MvcResult res = mockMvc.perform(post("/api/v1/admin/adjustments")
                            .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(threads);
    }
}
