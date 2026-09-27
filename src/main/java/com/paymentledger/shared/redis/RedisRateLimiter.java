package com.paymentledger.shared.redis;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Distributed, atomic, TTL-based rate limiter using Redis and atomic Lua scripting.
 * <p>
 * GUARANTEES:
 * <ul>
 *   <li>Atomic: Executed as a single-threaded Lua script in Redis. No race conditions under high concurrency.</li>
 *   <li>Distributed: State is shared across all application instances connected to Redis.</li>
 *   <li>TTL-Bounded: Every key has an absolute expiry matching the evaluation window.</li>
 *   <li>Resilient: Explicit configurable {@link RateLimitPolicy#FAIL_OPEN} and {@link RateLimitPolicy#FAIL_CLOSED} policies during Redis downtime.</li>
 *   <li>Non-authoritative: Failure of rate limiting NEVER corrupts financial or ledger state.</li>
 * </ul>
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String RATE_LIMIT_LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local limit = tonumber(ARGV[1])\n" +
            "local windowSeconds = tonumber(ARGV[2])\n" +
            "\n" +
            "local current = redis.call('INCR', key)\n" +
            "if current == 1 then\n" +
            "    redis.call('EXPIRE', key, windowSeconds)\n" +
            "end\n" +
            "\n" +
            "local ttl = redis.call('TTL', key)\n" +
            "if ttl == -1 then\n" +
            "    redis.call('EXPIRE', key, windowSeconds)\n" +
            "    ttl = windowSeconds\n" +
            "end\n" +
            "\n" +
            "local allowed = 1\n" +
            "if current > limit then\n" +
            "    allowed = 0\n" +
            "end\n" +
            "\n" +
            "return {allowed, current, ttl}\n";

    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;
    private final RedisScript<List> script;

    @Autowired
    public RedisRateLimiter(@Autowired(required = false) StringRedisTemplate stringRedisTemplate,
                            @Autowired(required = false) RedisConnectionFactory connectionFactory,
                            @Autowired(required = false) MeterRegistry meterRegistry) {
        if (stringRedisTemplate != null) {
            this.stringRedisTemplate = stringRedisTemplate;
        } else if (connectionFactory != null) {
            this.stringRedisTemplate = new StringRedisTemplate(connectionFactory);
        } else {
            this.stringRedisTemplate = null;
        }
        this.meterRegistry = meterRegistry;

        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(RATE_LIMIT_LUA_SCRIPT);
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    /**
     * Evaluates a rate limit against a given key.
     *
     * @param key           Redis rate limit key (from {@link RedisKeyNamespaces})
     * @param limit         maximum allowed requests in window
     * @param windowSeconds duration of window in seconds
     * @param policy        fallback policy if Redis is unreachable
     * @return rate limit evaluation result
     */
    public RateLimitResult checkLimit(String key, long limit, long windowSeconds, RateLimitPolicy policy) {
        if (stringRedisTemplate == null) {
            return handleDegradation(key, limit, windowSeconds, policy, new IllegalStateException("Redis is not configured"));
        }

        try {
            List<?> result = stringRedisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );

            if (result == null || result.size() < 3) {
                log.warn("Redis rate limiter script returned invalid result for key {}", key);
                return handleDegradation(key, limit, windowSeconds, policy, new IllegalStateException("Unexpected script response"));
            }

            long allowed = ((Number) result.get(0)).longValue();
            long current = ((Number) result.get(1)).longValue();
            long ttl = ((Number) result.get(2)).longValue();

            if (allowed == 1L) {
                recordMetric("redis.ratelimit.allowed", "policy", policy.name());
                long remaining = Math.max(0, limit - current);
                return RateLimitResult.allowed(current, limit, remaining, ttl);
            } else {
                recordMetric("redis.ratelimit.rejected", "policy", policy.name());
                return RateLimitResult.rejected(current, limit, ttl);
            }
        } catch (Exception ex) {
            return handleDegradation(key, limit, windowSeconds, policy, ex);
        }
    }

    private RateLimitResult handleDegradation(String key, long limit, long windowSeconds, RateLimitPolicy policy, Exception ex) {
        recordMetric("redis.ratelimit.errors", "policy", policy.name());

        if (policy == RateLimitPolicy.FAIL_OPEN) {
            log.warn("Redis unavailable during rate limit evaluation for key {}. Failing OPEN per policy: {}", key, ex.getMessage());
            recordMetric("redis.ratelimit.allowed", "policy", policy.name(), "degraded", "true");
            return RateLimitResult.failOpenDegraded(limit, windowSeconds);
        } else {
            log.error("Redis unavailable during rate limit evaluation for key {}. Failing CLOSED per policy: {}", key, ex.getMessage());
            recordMetric("redis.ratelimit.rejected", "policy", policy.name(), "degraded", "true");
            return RateLimitResult.failClosedDegraded(limit, windowSeconds);
        }
    }

    private void recordMetric(String name, String... tags) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(name, tags).increment();
            } catch (Exception ignored) {
            }
        }
    }
}
