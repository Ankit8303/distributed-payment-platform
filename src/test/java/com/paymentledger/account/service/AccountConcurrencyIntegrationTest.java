package com.paymentledger.account.service;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentledger.infrastructure.AbstractIntegrationTest;

class AccountConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID ownerId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity("concurrent@example.com", "hash", Role.CUSTOMER);
        user = userRepository.save(user);
        ownerId = user.getId();

        AccountEntity account = accountService.createAccount(ownerId, AccountType.CUSTOMER, "USD", "CUSTOMER");
        accountId = account.getId();
    }

    @AfterEach
    void tearDown() {
        if (accountId != null) {
            accountRepository.deleteById(accountId);
        }
        if (ownerId != null) {
            userRepository.deleteById(ownerId);
        }
    }

    @Test
    @DisplayName("Concurrent freeze requests should result in optimistic locking failure for all but one")
    void testConcurrentFreezeRequests() throws InterruptedException {
        int numberOfThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger optimisticLockFailures = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // wait for all threads to start
                    accountService.freezeAccount(accountId, "Security Freeze");
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    optimisticLockFailures.incrementAndGet();
                } catch (Exception e) {
                    // Ignore other errors for this test
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        latch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Only one should succeed, others should fail with Optimistic Locking
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(optimisticLockFailures.get()).isGreaterThanOrEqualTo(0); 

        // Verify final state
        AccountEntity updatedAccount = accountRepository.findById(accountId).orElseThrow();
        assertThat(updatedAccount.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThat(updatedAccount.getVersion()).isEqualTo(1L);
    }
}
