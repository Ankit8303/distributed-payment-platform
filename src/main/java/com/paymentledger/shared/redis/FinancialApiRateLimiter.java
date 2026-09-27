package com.paymentledger.shared.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies operation-scoped distributed rate limits to authenticated financial mutations.
 *
 * The caller supplies an authenticated, non-secret actor identifier (normally the user UUID).
 * Redis remains non-authoritative: this component only controls request admission.
 */
@Component
public class FinancialApiRateLimiter {

    private final RedisRateLimiter redisRateLimiter;
    private final long limit;
    private final long windowSeconds;

    public FinancialApiRateLimiter(
            RedisRateLimiter redisRateLimiter,
            @Value("${app.ratelimit.financial.limit:30}") long limit,
            @Value("${app.ratelimit.financial.window-seconds:60}") long windowSeconds) {
        if (limit <= 0) {
            throw new IllegalArgumentException("app.ratelimit.financial.limit must be positive");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("app.ratelimit.financial.window-seconds must be positive");
        }
        this.redisRateLimiter = redisRateLimiter;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public RateLimitResult check(FinancialRateLimitOperation operation, String actorIdentifier) {
        if (operation == null) {
            throw new IllegalArgumentException("operation cannot be null");
        }
        String key = RedisKeyNamespaces.financialApiRateLimitKey(operation, actorIdentifier);
        return redisRateLimiter.checkLimit(key, limit, windowSeconds, RateLimitPolicy.FAIL_CLOSED);
    }

    public void enforce(FinancialRateLimitOperation operation, String actorIdentifier) {
        RateLimitResult result = check(operation, actorIdentifier);
        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    "Too many financial API requests. Please retry later.",
                    result.resetSeconds(),
                    result.limit()
            );
        }
    }
}
