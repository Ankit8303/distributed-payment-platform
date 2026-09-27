package com.paymentledger.shared.redis;

/**
 * Failure degradation policy when Redis is unavailable during rate limiting.
 */
public enum RateLimitPolicy {
    /**
     * If Redis is unreachable, fail open (allow the request).
     * Used for non-critical or standard business read endpoints where availability > rate limiting.
     */
    FAIL_OPEN,

    /**
     * If Redis is unreachable, fail closed (reject the request).
     * Used for security-critical endpoints (e.g. authentication abuse, credential stuffing defense).
     */
    FAIL_CLOSED
}
