package com.paymentledger.auth.service;

import com.paymentledger.auth.config.JwtProperties;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.dto.LoginResponse;
import com.paymentledger.auth.dto.RefreshTokenRequest;
import com.paymentledger.auth.exception.InvalidRefreshTokenException;
import com.paymentledger.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentledger.infrastructure.AbstractIntegrationTest;

class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtProperties jwtProperties;

    private UserEntity testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserEntity("concurrent-test-" + UUID.randomUUID() + "@example.com", "hash", Role.CUSTOMER);
        userRepository.save(testUser);
    }

    @Test
    @DisplayName("refresh_sameTokenUsedTwice_secondAttemptFails")
    void refresh_sameTokenUsedTwice_secondAttemptFails() {
        // Issue initial tokens by "logging in" or directly calling the private issue method if possible.
        // For testing, we'll simulate a login or use reflection if needed, but since issueTokenPair is private,
        // we'll create a token via a dummy request or by temporarily changing access.
        // Actually, we can just register and get a token pair, but register doesn't return tokens.
        // We will test by doing a proper flow.

        // Since we don't have the plaintext password for login, we can't easily login.
        // Let's create a new user properly.
        com.paymentledger.auth.dto.RegisterRequest regReq = new com.paymentledger.auth.dto.RegisterRequest("replay-" + UUID.randomUUID() + "@example.com", "password1234", "CUSTOMER");
        authService.register(regReq);
        
        com.paymentledger.auth.dto.LoginRequest logReq = new com.paymentledger.auth.dto.LoginRequest(regReq.getEmail(), regReq.getPassword());
        LoginResponse loginResp = authService.login(logReq);
        String refreshToken = loginResp.getRefreshToken();

        RefreshTokenRequest refreshReq = new RefreshTokenRequest(refreshToken);

        // First attempt should succeed
        LoginResponse refreshResp = authService.refreshToken(refreshReq);
        assertThat(refreshResp.getAccessToken()).isNotNull();

        // Second attempt with the SAME token should fail
        assertThatThrownBy(() -> authService.refreshToken(refreshReq))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("refresh_concurrentSameToken_onlyOneSucceeds")
    void refresh_concurrentSameToken_onlyOneSucceeds() throws InterruptedException {
        // Setup user and initial token
        com.paymentledger.auth.dto.RegisterRequest regReq = new com.paymentledger.auth.dto.RegisterRequest("concurrent-" + UUID.randomUUID() + "@example.com", "password1234", "CUSTOMER");
        authService.register(regReq);
        
        com.paymentledger.auth.dto.LoginRequest logReq = new com.paymentledger.auth.dto.LoginRequest(regReq.getEmail(), regReq.getPassword());
        LoginResponse loginResp = authService.login(logReq);
        String refreshToken = loginResp.getRefreshToken();

        RefreshTokenRequest refreshReq = new RefreshTokenRequest(refreshToken);

        int numThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executorService.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    authService.refreshToken(refreshReq);
                    successCount.incrementAndGet();
                } catch (InvalidRefreshTokenException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        doneLatch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // EXACTLY ONE request should succeed, the rest MUST fail.
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(numThreads - 1);
    }
}
