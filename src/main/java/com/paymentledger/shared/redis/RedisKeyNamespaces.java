package com.paymentledger.shared.redis;

import java.util.UUID;

/**
 * Deterministic, versioned, collision-resistant Redis key namespaces.
 * <p>
 * Key structure convention:
 * {@code {classification}:{domain}:v{version}:{entityId}}
 * <p>
 * ABSOLUTE NON-NEGOTIABLE SECURITY RULES:
 * <ul>
 *   <li>Never store secrets, passwords, JWT tokens, refresh tokens, private keys, or raw payment card data in keys.</li>
 *   <li>Never use un-namespaced or unversioned keys (e.g., "account:123").</li>
 *   <li>Redis is strictly auxiliary and disposable; PostgreSQL remains the sole financial authority.</li>
 * </ul>
 */
public final class RedisKeyNamespaces {

    private RedisKeyNamespaces() {
    }

    public static final String CACHE_ACCOUNT_PREFIX = "cache:account:v1:";
    public static final String RATE_LIMIT_AUTH_PREFIX = "rate-limit:auth:v1:";
    public static final String RATE_LIMIT_API_PREFIX = "rate-limit:api:v1:";

    /**
     * Generates a deterministic namespaced key for account read-caching.
     *
     * @param accountId account UUID
     * @return key in format {@code cache:account:v1:{accountId}}
     */
    public static String accountCacheKey(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId cannot be null");
        }
        return CACHE_ACCOUNT_PREFIX + accountId;
    }

    /**
     * Generates a deterministic namespaced key for authentication rate limiting.
     *
     * @param actorIdentifier client identifier (e.g. normalized email or IP)
     * @return key in format {@code rate-limit:auth:v1:{actorIdentifier}}
     */
    public static String authRateLimitKey(String actorIdentifier) {
        if (actorIdentifier == null || actorIdentifier.isBlank()) {
            throw new IllegalArgumentException("actorIdentifier cannot be blank");
        }
        return RATE_LIMIT_AUTH_PREFIX + actorIdentifier.trim().toLowerCase();
    }

    /**
     * Generates a deterministic namespaced key for general API endpoint rate limiting.
     *
     * @param actorIdentifier client or client IP identifier
     * @return key in format {@code rate-limit:api:v1:{actorIdentifier}}
     */
    public static String apiRateLimitKey(String actorIdentifier) {
        if (actorIdentifier == null || actorIdentifier.isBlank()) {
            throw new IllegalArgumentException("actorIdentifier cannot be blank");
        }
        return RATE_LIMIT_API_PREFIX + actorIdentifier.trim().toLowerCase();
    }
}
