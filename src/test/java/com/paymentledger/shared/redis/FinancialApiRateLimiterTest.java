package com.paymentledger.shared.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialApiRateLimiterTest {

    @Mock
    private RedisRateLimiter redisRateLimiter;

    private FinancialApiRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new FinancialApiRateLimiter(redisRateLimiter, 30, 60);
    }

    @Test
    void checkUsesOperationScopedAuthenticatedActorKeyAndFailClosedPolicy() {
        RateLimitResult result = RateLimitResult.allowed(1, 30, 29, 60);
        when(redisRateLimiter.checkLimit(
                eq("rate-limit:financial:v1:payment:user-123"), eq(30L), eq(60L), eq(RateLimitPolicy.FAIL_CLOSED)))
                .thenReturn(result);

        assertThat(rateLimiter.check(FinancialRateLimitOperation.PAYMENT, "user-123"))
                .isSameAs(result);

        verify(redisRateLimiter).checkLimit(
                "rate-limit:financial:v1:payment:user-123", 30, 60, RateLimitPolicy.FAIL_CLOSED);
    }

    @Test
    void operationsUseIndependentBuckets() {
        RateLimitResult result = RateLimitResult.allowed(1, 30, 29, 60);
        when(redisRateLimiter.checkLimit(
                eq("rate-limit:financial:v1:payment:user-123"), eq(30L), eq(60L), eq(RateLimitPolicy.FAIL_CLOSED)))
                .thenReturn(result);
        when(redisRateLimiter.checkLimit(
                eq("rate-limit:financial:v1:payout:user-123"), eq(30L), eq(60L), eq(RateLimitPolicy.FAIL_CLOSED)))
                .thenReturn(result);

        rateLimiter.check(FinancialRateLimitOperation.PAYMENT, "user-123");
        rateLimiter.check(FinancialRateLimitOperation.PAYOUT, "user-123");

        verify(redisRateLimiter).checkLimit(
                "rate-limit:financial:v1:payment:user-123", 30, 60, RateLimitPolicy.FAIL_CLOSED);
        verify(redisRateLimiter).checkLimit(
                "rate-limit:financial:v1:payout:user-123", 30, 60, RateLimitPolicy.FAIL_CLOSED);
    }

    @Test
    void enforceThrowsExistingRateLimitExceptionWhenRejected() {
        when(redisRateLimiter.checkLimit(
                eq("rate-limit:financial:v1:refund:user-123"), eq(30L), eq(60L), eq(RateLimitPolicy.FAIL_CLOSED)))
                .thenReturn(RateLimitResult.rejected(31, 30, 17));

        assertThatThrownBy(() -> rateLimiter.enforce(FinancialRateLimitOperation.REFUND, "user-123"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Too many financial API requests");
    }

    @Test
    void redisDegradationRemainsFailClosed() {
        RateLimitResult degraded = RateLimitResult.failClosedDegraded(30, 60);
        when(redisRateLimiter.checkLimit(
                eq("rate-limit:financial:v1:reversal:user-123"), eq(30L), eq(60L), eq(RateLimitPolicy.FAIL_CLOSED)))
                .thenReturn(degraded);

        assertThat(rateLimiter.check(FinancialRateLimitOperation.REVERSAL, "user-123").allowed())
                .isFalse();
        assertThat(rateLimiter.check(FinancialRateLimitOperation.REVERSAL, "user-123").degraded())
                .isTrue();
    }
}
