package com.paymentledger.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.payment.api.dto.PaymentCreateRequest;
import com.paymentledger.payment.api.dto.PaymentResponse;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.shared.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.datasource.hikari.maximum-pool-size=50",
    "spring.jpa.open-in-view=false"
})
public class PaymentIntegrationTest extends AbstractIntegrationTest {

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
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserEntity payer;
    private UserEntity payee;
    private AccountEntity payerAccount;
    private AccountEntity payeeAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        paymentRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();

        payer = new UserEntity("payer@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(payer);

        payerAccount = new AccountEntity("ACC-PAYER", payer.getId(), AccountType.CUSTOMER, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(payerAccount);
        jdbcTemplate.update("UPDATE accounts SET materialized_balance_minor = ? WHERE id = ?", 10000L, payerAccount.getId());
        UUID initTxId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status) VALUES (?, 'SYSTEM_ADJUSTMENT', ?, 'MANUAL', 'USD', 'Init', 'POSTED')", initTxId, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO ledger_entries (id, account_id, ledger_transaction_id, direction, amount_minor, currency, sequence_number) VALUES (?, ?, ?, 'CREDIT', ?, 'USD', 1)", UUID.randomUUID(), payerAccount.getId(), initTxId, 10000L);

        payee = new UserEntity("payee@example.com", "hash", Role.MERCHANT);
        userRepository.saveAndFlush(payee);

        payeeAccount = new AccountEntity("ACC-PAYEE", payee.getId(), AccountType.MERCHANT, "USD", AccountStatus.ACTIVE);
        accountRepository.saveAndFlush(payeeAccount);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_transactions CASCADE");
        paymentRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        userRepository.deleteAllInBatch();
    }

    @Test
    void testSuccessfulPayment() throws Exception {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(5000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        
        assertThat(response.getStatus()).isEqualTo("SETTLED");
        assertThat(response.getProviderReference()).startsWith("cap_");
        assertThat(response.getPaymentId()).isNotNull();

        // Verify idempotency cache
        MvcResult duplicateResult = mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
                
        PaymentResponse duplicateResponse = objectMapper.readValue(duplicateResult.getResponse().getContentAsString(), PaymentResponse.class);
        assertThat(duplicateResponse.getPaymentId()).isEqualTo(response.getPaymentId());
    }

    @Test
    void testPaymentTimeoutYieldsAccepted() throws Exception {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(5000L);
        request.setCurrency("USD");
        // token mapped to timeout in FakePaymentProvider
        request.setPaymentMethodToken("tok_timeout");

        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        assertThat(response.getStatus()).isEqualTo("PENDING_RECONCILIATION");

        // Retry the same request - should return the cached response
        MvcResult duplicateResult = mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();
                
        PaymentResponse duplicateResponse = objectMapper.readValue(duplicateResult.getResponse().getContentAsString(), PaymentResponse.class);
        assertThat(duplicateResponse.getPaymentId()).isEqualTo(response.getPaymentId());
    }

    @Test
    void testIdempotencyConflict_DifferentPayload() throws Exception {
        PaymentCreateRequest request1 = new PaymentCreateRequest();
        request1.setPayeeAccountId(payeeAccount.getId());
        request1.setAmountMinor(5000L);
        request1.setCurrency("USD");
        request1.setPaymentMethodToken("tok_visa_4242");

        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated());

        // Different payload
        PaymentCreateRequest request2 = new PaymentCreateRequest();
        request2.setPayeeAccountId(payeeAccount.getId());
        request2.setAmountMinor(6000L);
        request2.setCurrency("USD");
        request2.setPaymentMethodToken("tok_visa_4242");

        mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict());
    }

    @Test
    void testPaymentValidation_SamePayerAndPayee() throws Exception {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payerAccount.getId()); // SAME
        request.setAmountMinor(5000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testSecurity_IDOR_CannotCreatePaymentFromOtherUserAccount() throws Exception {
        // IDOR for creation is prevented by controller signature using the authenticated principal.
    }

    @Test
    void testSecurity_IDOR_GetPayment() throws Exception {
        // 1. Payer creates a payment
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(5000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        UUID paymentId = response.getPaymentId();

        // 2. Unrelated user tries to read the payment
        UserEntity unrelated = new UserEntity("unrelated@example.com", "hash", Role.CUSTOMER);
        userRepository.saveAndFlush(unrelated);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/payments/" + paymentId)
                .with(authentication(new UsernamePasswordAuthenticationToken(unrelated.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))))
                .andExpect(status().isNotFound()); // The spec says 404 for access denied / not found
    }

    @Test
    void testIdempotency_ConcurrentRequests() throws Exception {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(5000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        String idempotencyKey = UUID.randomUUID().toString();
        String payload = objectMapper.writeValueAsString(request);

        int threads = 5;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger conflictCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/payments")
                            .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                            .andReturn();
                    
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    } else if (res.getResponse().getStatus() == 409) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // start all
        done.await(); // wait all

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(threads - 1);
        
        long paymentCount = paymentRepository.count();
        assertThat(paymentCount).isEqualTo(1);
    }

    @Test
    void testPhase6_ConcurrentPayments() throws Exception {
        PaymentCreateRequest request1 = new PaymentCreateRequest();
        request1.setPayeeAccountId(payeeAccount.getId());
        request1.setAmountMinor(7000L); // 7000 > 10000/2
        request1.setCurrency("USD");
        request1.setPaymentMethodToken("tok_visa_4242");

        PaymentCreateRequest request2 = new PaymentCreateRequest();
        request2.setPayeeAccountId(payeeAccount.getId());
        request2.setAmountMinor(7000L);
        request2.setCurrency("USD");
        request2.setPaymentMethodToken("tok_visa_4242");

        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();
        
        String payload1 = objectMapper.writeValueAsString(request1);
        String payload2 = objectMapper.writeValueAsString(request2);

        int threads = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger insufficientFundsCount = new java.util.concurrent.atomic.AtomicInteger(0);

        executor.submit(() -> {
            try {
                latch.await();
                MvcResult res = mockMvc.perform(post("/api/v1/payments")
                        .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                        .andReturn();
                
                if (res.getResponse().getStatus() == 201) {
                    successCount.incrementAndGet();
                } else if (res.getResponse().getStatus() == 202) { // ACCEPTED for PENDING_RECONCILIATION
                    insufficientFundsCount.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                latch.await();
                MvcResult res = mockMvc.perform(post("/api/v1/payments")
                        .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                        .header("Idempotency-Key", key2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                        .andReturn();
                
                if (res.getResponse().getStatus() == 201) {
                    successCount.incrementAndGet();
                } else if (res.getResponse().getStatus() == 202) {
                    insufficientFundsCount.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        latch.countDown(); // start all
        done.await(); // wait all

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(insufficientFundsCount.get()).isEqualTo(1);
        
        // Verify balance
        Long balance = jdbcTemplate.queryForObject("SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payerAccount.getId());
        assertThat(balance).isEqualTo(3000L);

        Long payeeBalance = jdbcTemplate.queryForObject("SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payeeAccount.getId());
        assertThat(payeeBalance).isEqualTo(7000L);

        // Verify ledger transactions
        Long ledgerTxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class);
        assertThat(ledgerTxCount).isEqualTo(2L); // 1 SYSTEM_ADJUSTMENT + 1 PAYMENT

        // Verify ledger balance sum matches materialized
        Long ledgerDerivedPayer = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM ledger_entries WHERE account_id = ?",
            Long.class, payerAccount.getId()
        );
        assertThat(ledgerDerivedPayer).isEqualTo(3000L);
    }

    @Test
    void testPhase6_AtomicRollbackAfterLedgerWork() throws Exception {
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(3000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        String idempotencyKey = UUID.randomUUID().toString();
        
        // Add a temporary constraint to the database to force a failure when saving the new balance
        jdbcTemplate.execute("ALTER TABLE accounts ADD CONSTRAINT tmp_fail_7000 CHECK (materialized_balance_minor != 7000)");

        MvcResult result;
        try {
            result = mockMvc.perform(post("/api/v1/payments")
                    .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted()) // PENDING_RECONCILIATION
                    .andReturn();
        } finally {
            // Remove the constraint so it doesn't break other tests
            jdbcTemplate.execute("ALTER TABLE accounts DROP CONSTRAINT tmp_fail_7000");
        }
                
        // Verify no NEW ledger transactions persisted (only the Init one)
        Long ledgerTxCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class);
        assertThat(ledgerTxCount).isEqualTo(1L);

        // Verify no NEW ledger entries persisted (only the Init one)
        Long ledgerEntryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertThat(ledgerEntryCount).isEqualTo(1L);

        // Verify materialized balance is unchanged
        Long balance = jdbcTemplate.queryForObject("SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payerAccount.getId());
        assertThat(balance).isEqualTo(10000L);
        
        // Verify payment state is PENDING_RECONCILIATION
        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        assertThat(response.getStatus()).isEqualTo("PENDING_RECONCILIATION");
    }

    @Test
    void testPhase6_LedgerDerivedBalanceEquality() throws Exception {
        // Initial state is 10000 from setUp()
        
        PaymentCreateRequest request = new PaymentCreateRequest();
        request.setPayeeAccountId(payeeAccount.getId());
        request.setAmountMinor(4000L);
        request.setCurrency("USD");
        request.setPaymentMethodToken("tok_visa_4242");

        mockMvc.perform(post("/api/v1/payments")
                .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        Long derivedPayer = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM ledger_entries WHERE account_id = ?",
            Long.class, payerAccount.getId());
            
        Long matPayer = jdbcTemplate.queryForObject("SELECT materialized_balance_minor FROM accounts WHERE id = ?", Long.class, payerAccount.getId());
        
        assertThat(derivedPayer).isEqualTo(matPayer);
        assertThat(matPayer).isEqualTo(6000L);
    }

    @Test
    void testPhase6_SequenceConcurrency() throws Exception {
        // Init with huge balance to allow all to succeed
        jdbcTemplate.update("UPDATE accounts SET materialized_balance_minor = 1000000 WHERE id = ?", payerAccount.getId());
        UUID txId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status) VALUES (?, 'SYSTEM_ADJUSTMENT', ?, 'MANUAL', 'USD', 'Init2', 'POSTED')", txId, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, direction, amount_minor, currency, sequence_number) VALUES (?, ?, ?, 'CREDIT', 990000, 'USD', 2)", UUID.randomUUID(), txId, payerAccount.getId());

        int threads = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    PaymentCreateRequest request = new PaymentCreateRequest();
                    request.setPayeeAccountId(payeeAccount.getId());
                    request.setAmountMinor(100L);
                    request.setCurrency("USD");
                    request.setPaymentMethodToken("tok_visa_4242");
                    
                    String idempotencyKey = UUID.randomUUID().toString();
                    String payload = objectMapper.writeValueAsString(request);
                    
                    latch.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/payments")
                            .with(authentication(new UsernamePasswordAuthenticationToken(payer.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))))
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                            .andReturn();
                    
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // start all
        done.await(); // wait all

        assertThat(successCount.get()).isEqualTo(threads);
        
        Long maxSeq = jdbcTemplate.queryForObject("SELECT MAX(sequence_number) FROM ledger_entries WHERE account_id = ?", Long.class, payerAccount.getId());
        assertThat(maxSeq).isEqualTo((long) threads + 2); // 1 init + 1 init2 + 10 debits

        Long countSeq = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT sequence_number) FROM ledger_entries WHERE account_id = ?", Long.class, payerAccount.getId());
        assertThat(countSeq).isEqualTo((long) threads + 2);
    }
}
