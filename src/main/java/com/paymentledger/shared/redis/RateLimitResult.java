package com.paymentledger.shared.redis;

/**
 * Result of a distributed rate limiting evaluation.
 *
 * @param allowed       whether the request is permitted
 * @param currentCount  the current request count in this window
 * @param limit         the maximum permitted requests in the window
 * @param remaining     the remaining permitted requests
 * @param resetSeconds  the seconds until the rate limit window resets
 * @param degraded      true if Redis was unreachable and a fallback degradation policy was applied
 */
public record RateLimitResult(
        boolean allowed,
        long currentCount,
        long limit,
        long remaining,
        long resetSeconds,
        boolean degraded
) {
    public static RateLimitResult allowed(long currentCount, long limit, long remaining, long resetSeconds) {
        return new RateLimitResult(true, currentCount, limit, remaining, resetSeconds, false);
    }

    public static RateLimitResult rejected(long currentCount, long limit, long resetSeconds) {
        return new RateLimitResult(false, currentCount, limit, 0, resetSeconds, false);
    }

    public static RateLimitResult failOpenDegraded(long limit, long resetSeconds) {
        return new RateLimitResult(true, 0, limit, limit, resetSeconds, true);
    }

    public static RateLimitResult failClosedDegraded(long limit, long resetSeconds) {
        return new RateLimitResult(false, limit + 1, limit, 0, resetSeconds, true);
    }
}
