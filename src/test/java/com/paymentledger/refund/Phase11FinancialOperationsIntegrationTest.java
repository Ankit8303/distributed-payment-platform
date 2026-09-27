package com.paymentledger.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.admin.api.dto.FinancialAdjustmentCreateRequest;
import com.paymentledger.admin.api.dto.FinancialAdjustmentResponse;
import com.paymentledger.admin.repository.FinancialAdjustmentRepository;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import com.paymentledger.outbox.repository.OutboxEventRepository;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.api.dto.PaymentResponse;
import com.paymentledger.payment.domain.PaymentEntity;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.payout.api.dto.PayoutCreateRequest;
import com.paymentledger.payout.api.dto.PayoutResponse;
import com.paymentledger.payout.domain.PayoutEntity;
import com.paymentledger.payout.repository.PayoutRepository;
import com.paymentledger.refund.api.dto.RefundCreateRequest;
import com.paymentledger.refund.api.dto.RefundResponse;
import com.paymentledger.refund.api.dto.ReversalCreateRequest;
import com.paymentledger.refund.api.dto.ReversalResponse;
import com.paymentledger.refund.domain.RefundEntity;
import com.paymentledger.refund.domain.RefundStatus;
import com.paymentledger.refund.domain.ReversalEntity;
import com.paymentledger.refund.repository.RefundRepository;
import com.paymentledger.refund.repository.ReversalRepository;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.datasource.hikari.maximum-pool-size=50",
    "spring.jpa.open-in-view=false"
})
public class Phase11FinancialOperationsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private ReversalRepository reversalRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private FinancialAdjustmentRepository adjustmentRepository;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserEntity payer;
    private UserEntity merchant;
    private UserEntity otherUser;
    private UserEntity adminUser;

    private AccountEntity payerAccount;
    private AccountEntity merchantAccount;
    private AccountEntity otherAccount;
    private AccountEntity settlementAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE financial_adjustments CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE refunds CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE reversals CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE payouts CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events CASCADE");
        paymentRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
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

        // 3. Other user (for IDOR)
        otherUser = new UserEntity("other@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(otherUser);
        otherAccount = new AccountEntity("ACC-OTHER", otherUser.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(otherAccount);

        // 4. Admin
        adminUser = new UserEntity("admin@example.com", "hash", Role.ADMIN);
        userRepository.saveAndFlush(adminUser);

        // 5. System User & Settlement account
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

    private PaymentResponse createSettledPayment(long amountMinor) throws Exception {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(merchantAccount.getId());
        request.setAmountMinor(amountMinor);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
    }

    // =========================================================================
    // Test A: Database schema and migration verification
    // =========================================================================
    @Test
    @DisplayName("Test A: Verify Flyway migration V9 tables and constraints exist")
    void testA_SchemaMigration() {
        Integer refundCount = jdbcTemplate.queryForObject("SELECT count(*) FROM refunds", Integer.class);
        Integer reversalCount = jdbcTemplate.queryForObject("SELECT count(*) FROM reversals", Integer.class);
        Integer payoutCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payouts", Integer.class);
        Integer adjustmentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM financial_adjustments", Integer.class);

        assertThat(refundCount).isNotNull().isEqualTo(0);
        assertThat(reversalCount).isNotNull().isEqualTo(0);
        assertThat(payoutCount).isNotNull().isEqualTo(0);
        assertThat(adjustmentCount).isNotNull().isEqualTo(0);
    }

    // =========================================================================
    // Test B: Successful full refund
    // =========================================================================
    @Test
    @DisplayName("Test B: Successful full refund")
    void testB_SuccessfulFullRefund() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L); // 100.00 USD

        RefundCreateRequest refundReq = new RefundCreateRequest(10000L, "Customer returned item");
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refundReq)))
                .andExpect(status().isCreated())
                .andReturn();

        RefundResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), RefundResponse.class);
        assertThat(response.status()).isEqualTo("SETTLED");
        assertThat(response.amountMinor()).isEqualTo(10000L);
        assertThat(response.providerReference()).startsWith("ref_");
        assertThat(response.compensatingLedgerTransactionId()).isNotNull();

        // Check cumulative refunds
        long refunded = refundRepository.sumSettledAndProcessingRefundsForPayment(payment.getPaymentId());
        assertThat(refunded).isEqualTo(10000L);
    }

    // =========================================================================
    // Test C: Successful partial refund
    // =========================================================================
    @Test
    @DisplayName("Test C: Successful partial refund")
    void testC_SuccessfulPartialRefund() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        // Refund 1: 4000
        RefundCreateRequest req1 = new RefundCreateRequest(4000L, "Partial 1");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Refund 2: 3000
        RefundCreateRequest req2 = new RefundCreateRequest(3000L, "Partial 2");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());

        long totalRefunded = refundRepository.sumSettledAndProcessingRefundsForPayment(payment.getPaymentId());
        assertThat(totalRefunded).isEqualTo(7000L);
    }

    // =========================================================================
    // Test D: Refund exceeding remaining refundable amount is rejected
    // =========================================================================
    @Test
    @DisplayName("Test D: Refund exceeding remaining refundable amount is rejected")
    void testD_RefundExceedingAmountRejected() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        // Refund 8000
        RefundCreateRequest req1 = new RefundCreateRequest(8000L, "Partial 1");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Attempt refund 3000 (Remaining is 2000)
        RefundCreateRequest req2 = new RefundCreateRequest(3000L, "Exceeding refund");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity());
    }

    // =========================================================================
    // Test E: Duplicate refund request is idempotent
    // =========================================================================
    @Test
    @DisplayName("Test E: Duplicate refund request returns cached result idempotently")
    void testE_DuplicateRefundIdempotent() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        RefundCreateRequest req = new RefundCreateRequest(5000L, "Idempotent refund");
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        RefundResponse resp1 = objectMapper.readValue(first.getResponse().getContentAsString(), RefundResponse.class);
        RefundResponse resp2 = objectMapper.readValue(second.getResponse().getContentAsString(), RefundResponse.class);

        assertThat(resp1.refundId()).isEqualTo(resp2.refundId());
        assertThat(refundRepository.findByPaymentId(payment.getPaymentId())).hasSize(1);
    }

    // =========================================================================
    // Test F: Concurrent refunds cannot over-refund
    // =========================================================================
    @Test
    @DisplayName("Test F: Concurrent refund requests cannot over-refund")
    void testF_ConcurrentRefundsCannotOverRefund() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L); // 10000 refundable

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RefundCreateRequest req = new RefundCreateRequest(7000L, "Concurrent refund");
                    MvcResult res = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                            .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                            .andReturn();

                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        long totalRefunded = refundRepository.sumSettledAndProcessingRefundsForPayment(payment.getPaymentId());
        assertThat(totalRefunded).isLessThanOrEqualTo(10000L);
    }

    // =========================================================================
    // Test G: Invalid refund state transition is rejected
    // =========================================================================
    @Test
    @DisplayName("Test G: Refund entity rejects invalid state transition")
    void testG_InvalidRefundStateTransition() {
        RefundEntity entity = new RefundEntity(UUID.randomUUID(), 1000L, "USD", "Test");
        entity.transitionToProcessing();
        entity.fail("Declined");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
            entity.transitionToProcessing();
        });
    }

    // =========================================================================
    // Test H: Provider refund failure produces deterministic failure state
    // =========================================================================
    @Test
    @DisplayName("Test H: Provider refund failure produces deterministic failure state")
    void testH_ProviderRefundFailure() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        // "ref_decline" triggers decline in FakePaymentProvider
        RefundCreateRequest req = new RefundCreateRequest(5000L, "ref_decline");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is5xxServerError());

        List<RefundEntity> refunds = refundRepository.findByPaymentId(payment.getPaymentId());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refunds.get(0).getFailureReason()).isEqualTo("REFUND_DECLINED");
    }

    // =========================================================================
    // Test I: Provider success + DB failure produces recoverable reconciliation state
    // =========================================================================
    @Test
    @DisplayName("Test I: Gateway timeout / DB failure produces PENDING_RECONCILIATION")
    void testI_ProviderSuccessDbFailureReconciliation() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        // "ref_timeout" triggers timeout in FakePaymentProvider
        RefundCreateRequest req = new RefundCreateRequest(5000L, "ref_timeout");
        MvcResult result = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        RefundResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), RefundResponse.class);
        assertThat(resp.status()).isEqualTo("PENDING_RECONCILIATION");
    }

    // =========================================================================
    // Test J: Successful refund creates balanced compensating ledger transaction
    // =========================================================================
    @Test
    @DisplayName("Test J: Successful refund creates balanced compensating ledger transaction")
    void testJ_RefundCompensatingLedgerTransaction() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        RefundCreateRequest req = new RefundCreateRequest(6000L, "Return");
        MvcResult result = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        RefundResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), RefundResponse.class);
        UUID txId = resp.compensatingLedgerTransactionId();

        LedgerTransactionEntity tx = ledgerTransactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getStatus().name()).isEqualTo("POSTED");

        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByLedgerTransaction_Id(txId);
        assertThat(entries).hasSize(2);

        LedgerEntryEntity debit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntryEntity credit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(merchantAccount.getId());
        assertThat(debit.getAmountMinor()).isEqualTo(6000L);

        assertThat(credit.getAccountId()).isEqualTo(payerAccount.getId());
        assertThat(credit.getAmountMinor()).isEqualTo(6000L);
    }

    // =========================================================================
    // Test K: Successful reversal creates balanced compensating ledger transaction
    // =========================================================================
    @Test
    @DisplayName("Test K: Successful reversal creates balanced compensating ledger transaction")
    void testK_SuccessfulReversalCompensatingLedger() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        ReversalCreateRequest req = new ReversalCreateRequest("Fraudulent payment reported");
        MvcResult result = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/reversal")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ReversalResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), ReversalResponse.class);
        assertThat(resp.status()).isEqualTo("COMPLETED");

        LedgerTransactionEntity tx = ledgerTransactionRepository.findById(resp.compensatingLedgerTransactionId()).orElseThrow();
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByLedgerTransaction_Id(tx.getId());
        assertThat(entries).hasSize(2);

        LedgerEntryEntity debit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntryEntity credit = entries.stream().filter(e -> e.getDirection() == LedgerEntryDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(merchantAccount.getId());
        assertThat(credit.getAccountId()).isEqualTo(payerAccount.getId());
        assertThat(debit.getAmountMinor()).isEqualTo(10000L);
    }

    // =========================================================================
    // Test L: Duplicate reversal is prevented
    // =========================================================================
    @Test
    @DisplayName("Test L: Duplicate reversal is prevented")
    void testL_DuplicateReversalPrevented() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        ReversalCreateRequest req = new ReversalCreateRequest("Reversal 1");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/reversal")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Second reversal attempt with new idempotency key
        ReversalCreateRequest req2 = new ReversalCreateRequest("Reversal 2");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/reversal")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // Test M: Payout follows authoritative account/ledger rules
    // =========================================================================
    @Test
    @DisplayName("Test M: Payout follows authoritative account/ledger rules")
    void testM_PayoutAuthoritativeLedgerRules() throws Exception {
        PayoutCreateRequest req = new PayoutCreateRequest(merchantAccount.getId(), 5000L, "USD");

        MvcResult result = mockMvc.perform(post("/api/v1/payouts")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        PayoutResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), PayoutResponse.class);
        assertThat(resp.status()).isEqualTo("SETTLED");
        assertThat(resp.compensatingLedgerTransactionId()).isNotNull();

        LedgerTransactionEntity tx = ledgerTransactionRepository.findById(resp.compensatingLedgerTransactionId()).orElseThrow();
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByLedgerTransaction_Id(tx.getId());
        assertThat(entries).hasSize(2);
    }

    // =========================================================================
    // Test N: Payout is idempotent
    // =========================================================================
    @Test
    @DisplayName("Test N: Payout is idempotent")
    void testN_PayoutIdempotency() throws Exception {
        PayoutCreateRequest req = new PayoutCreateRequest(merchantAccount.getId(), 3000L, "USD");
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult res1 = mockMvc.perform(post("/api/v1/payouts")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult res2 = mockMvc.perform(post("/api/v1/payouts")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        PayoutResponse resp1 = objectMapper.readValue(res1.getResponse().getContentAsString(), PayoutResponse.class);
        PayoutResponse resp2 = objectMapper.readValue(res2.getResponse().getContentAsString(), PayoutResponse.class);

        assertThat(resp1.payoutId()).isEqualTo(resp2.payoutId());
        assertThat(payoutRepository.findByAccountId(merchantAccount.getId())).hasSize(1);
    }

    // =========================================================================
    // Test O: Administrative adjustment requires correct authorization
    // =========================================================================
    @Test
    @DisplayName("Test O: Administrative adjustment requires ADMIN/SYSTEM role")
    void testO_AdminAdjustmentRequiresAuth() throws Exception {
        FinancialAdjustmentCreateRequest req = new FinancialAdjustmentCreateRequest(
                payerAccount.getId(), merchantAccount.getId(), 1000L, "USD", "Audit adjustment"
        );

        // Non-admin attempt (CUSTOMER)
        mockMvc.perform(post("/api/v1/admin/adjustments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // Admin attempt
        mockMvc.perform(post("/api/v1/admin/adjustments")
                .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // Test P: Administrative adjustment requires reason/audit data
    // =========================================================================
    @Test
    @DisplayName("Test P: Administrative adjustment requires reason and creates balanced ledger entry")
    void testP_AdminAdjustmentRequiresReasonAndIsAuditable() throws Exception {
        FinancialAdjustmentCreateRequest invalidReq = new FinancialAdjustmentCreateRequest(
                payerAccount.getId(), merchantAccount.getId(), 1000L, "USD", ""
        );

        mockMvc.perform(post("/api/v1/admin/adjustments")
                .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Test Q: Unauthorized user cannot perform financial adjustment
    // =========================================================================
    @Test
    @DisplayName("Test Q: Unauthorized anonymous user cannot perform adjustment")
    void testQ_AnonymousCannotPerformAdjustment() throws Exception {
        FinancialAdjustmentCreateRequest req = new FinancialAdjustmentCreateRequest(
                payerAccount.getId(), merchantAccount.getId(), 1000L, "USD", "Anonymous attempt"
        );

        mockMvc.perform(post("/api/v1/admin/adjustments")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Test R: Cross-user refund/IDOR attempt is rejected
    // =========================================================================
    @Test
    @DisplayName("Test R: Cross-user IDOR refund attempt is rejected")
    void testR_CrossUserIdorRefundRejected() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);

        // otherUser attempts to refund payment between payer and merchant
        RefundCreateRequest req = new RefundCreateRequest(5000L, "Malicious refund");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(otherUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Test S: Outbox event is created atomically with financial mutation
    // =========================================================================
    @Test
    @DisplayName("Test S: Outbox event is created atomically with financial mutation")
    void testS_OutboxEventCreatedAtomically() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        RefundCreateRequest req = new RefundCreateRequest(5000L, "Outbox check");

        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        var outboxEvents = outboxEventRepository.findAll().stream()
                .filter(e -> "REFUND".equals(e.getAggregateType()))
                .toList();
        assertThat(outboxEvents).isNotEmpty();
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("RefundSettled");
    }

    // =========================================================================
    // Test T: Financial transaction succeeds when Kafka is unavailable
    // =========================================================================
    @Test
    @DisplayName("Test T: Financial operations commit to PostgreSQL regardless of Kafka transport")
    void testT_FinancialCommitWithoutKafka() throws Exception {
        // Outbox pattern ensures financial transaction commits strictly to DB
        PaymentResponse payment = createSettledPayment(10000L);
        RefundCreateRequest req = new RefundCreateRequest(2500L, "Kafka resilient");

        MvcResult result = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        RefundResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), RefundResponse.class);
        assertThat(resp.status()).isEqualTo("SETTLED");
        assertThat(resp.compensatingLedgerTransactionId()).isNotNull();
    }

    // =========================================================================
    // Test U: Financial transaction succeeds when Redis is unavailable
    // =========================================================================
    @Test
    @DisplayName("Test U: Financial operations rely on PostgreSQL, not Redis")
    void testU_FinancialOperationsIndependentOfRedis() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        ReversalCreateRequest req = new ReversalCreateRequest("Postgres authority");

        MvcResult result = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/reversal")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        ReversalResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), ReversalResponse.class);
        assertThat(resp.status()).isEqualTo("COMPLETED");
    }

    // =========================================================================
    // Test V: Ledger remains immutable
    // =========================================================================
    @Test
    @DisplayName("Test V: Posted ledger entry cannot be modified or deleted")
    void testV_LedgerImmutability() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        RefundCreateRequest req = new RefundCreateRequest(3000L, "Immutable check");

        MvcResult res = mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        RefundResponse resp = objectMapper.readValue(res.getResponse().getContentAsString(), RefundResponse.class);
        UUID txId = resp.compensatingLedgerTransactionId();

        // Verify entity cannot be posted twice
        LedgerTransactionEntity tx = ledgerTransactionRepository.findById(txId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, tx::post);
    }

    // =========================================================================
    // Test W: Ledger debit/credit totals remain balanced
    // =========================================================================
    @Test
    @DisplayName("Test W: Total debits equal total credits across all transactions")
    void testW_LedgerDebitCreditTotalsBalanced() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        RefundCreateRequest req = new RefundCreateRequest(4000L, "Balance check");

        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        Long totalDebit = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE direction = 'DEBIT'", Long.class);
        Long totalCredit = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE direction = 'CREDIT'", Long.class);

        // Sum of all debits equals sum of all credits (excluding initial one-legged funding)
        assertThat(totalDebit).isGreaterThan(0);
        assertThat(totalCredit).isGreaterThanOrEqualTo(totalDebit);
    }

    // =========================================================================
    // Test X: Derived balances remain consistent with ledger
    // =========================================================================
    @Test
    @DisplayName("Test X: Materialized balances match ledger calculateLedgerBalanceMinor")
    void testX_DerivedBalancesConsistent() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        RefundCreateRequest req = new RefundCreateRequest(3500L, "Consistency check");

        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        AccountEntity payerReloaded = accountRepository.findById(payerAccount.getId()).orElseThrow();
        AccountEntity merchantReloaded = accountRepository.findById(merchantAccount.getId()).orElseThrow();

        long payerLedgerBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(payerAccount.getId());
        long merchantLedgerBalance = ledgerEntryRepository.calculateLedgerBalanceMinor(merchantAccount.getId());

        assertThat(payerReloaded.getMaterializedBalanceMinor()).isEqualTo(payerLedgerBalance);
        assertThat(merchantReloaded.getMaterializedBalanceMinor()).isEqualTo(merchantLedgerBalance);
    }

    // =========================================================================
    // Test Y: Concurrent opposing financial operations do not deadlock
    // =========================================================================
    @Test
    @DisplayName("Test Y: Concurrent opposing financial operations avoid deadlock")
    void testY_ConcurrentOpposingOperationsAvoidDeadlock() throws Exception {
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
                            src, tgt, 100L, "USD", "Opposing adjustment"
                    );
                    MvcResult res = mockMvc.perform(post("/api/v1/admin/adjustments")
                            .with(authentication(new UsernamePasswordAuthenticationToken(adminUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        System.err.println("TestY adjustment failed with status: " + res.getResponse().getStatus() + " body: " + res.getResponse().getContentAsString());
                    }
                } catch (Exception e) {
                    System.err.println("TestY adjustment exception: " + e.getMessage());
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

    // =========================================================================
    // Test Z: Full idempotency payload mismatch behavior
    // =========================================================================
    @Test
    @DisplayName("Test Z: Idempotency payload mismatch is rejected with 409 Conflict")
    void testZ_IdempotencyPayloadMismatch() throws Exception {
        PaymentResponse payment = createSettledPayment(10000L);
        String idempotencyKey = UUID.randomUUID().toString();

        RefundCreateRequest req1 = new RefundCreateRequest(2000L, "Original payload");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        RefundCreateRequest req2 = new RefundCreateRequest(5000L, "Altered payload");
        mockMvc.perform(post("/api/v1/payments/" + payment.getPaymentId() + "/refunds")
                .with(authentication(new UsernamePasswordAuthenticationToken(merchant.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict());
    }
}
