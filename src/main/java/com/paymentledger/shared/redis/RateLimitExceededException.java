package com.paymentledger.shared.redis;

/**
 * Exception thrown when a client exceeds their rate limit threshold.
 * Mapped to HTTP 429 Too Many Requests in GlobalExceptionHandler.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final long limit;

    public RateLimitExceededException(String message, long retryAfterSeconds, long limit) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit = limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public long getLimit() {
        return limit;
    }
}
