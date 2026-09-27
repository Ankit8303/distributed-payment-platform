package com.paymentledger.shared.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.account.domain.AccountEntity;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache-aside auxiliary service for account read models.
 * <p>
 * ABSOLUTE INVARIANTS:
 * <ul>
 *   <li>PostgreSQL is the sole financial authority; Redis is disposable.</li>
 *   <li>Cache miss or Redis outage falls back seamlessly to PostgreSQL.</li>
 *   <li>Cached data is NEVER used for financial balance checks, settlement, or debit decisions.</li>
 * </ul>
 */
@Service
public class AccountReadCacheService {

    private static final Logger log = LoggerFactory.getLogger(AccountReadCacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;

    @Autowired
    public AccountReadCacheService(@Autowired(required = false) RedisTemplate<String, Object> redisTemplate,
                                   @Autowired(required = false) MeterRegistry meterRegistry,
                                   @Autowired(required = false) ObjectMapper objectMapper,
                                   @Value("${app.cache.account.ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.defaultTtl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Reads an account from Redis cache.
     *
     * @param accountId account UUID
     * @return Optional containing {@link CachedAccountDto} if found, or empty on miss / Redis failure
     */
    public Optional<CachedAccountDto> get(UUID accountId) {
        if (redisTemplate == null || accountId == null) {
            return Optional.empty();
        }

        String key = RedisKeyNamespaces.accountCacheKey(accountId);
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                recordMetric("redis.cache.misses");
                return Optional.empty();
            }

            CachedAccountDto dto;
            if (raw instanceof CachedAccountDto) {
                dto = (CachedAccountDto) raw;
            } else {
                dto = objectMapper.convertValue(raw, CachedAccountDto.class);
            }

            recordMetric("redis.cache.hits");
            return Optional.of(dto);
        } catch (Exception ex) {
            log.warn("Redis error on account cache GET for key {}: {}. Falling back to PostgreSQL.", key, ex.getMessage());
            recordMetric("redis.cache.errors");
            return Optional.empty();
        }
    }

    /**
     * Writes an account to Redis cache with the default TTL.
     *
     * @param account authoritative account entity
     */
    public void put(AccountEntity account) {
        putWithTtl(account, defaultTtl);
    }

    /**
     * Writes an account to Redis cache with a custom TTL.
     *
     * @param account authoritative account entity
     * @param ttl     custom TTL duration
     */
    public void putWithTtl(AccountEntity account, Duration ttl) {
        if (redisTemplate == null || account == null || account.getId() == null) {
            return;
        }

        String key = RedisKeyNamespaces.accountCacheKey(account.getId());
        try {
            CachedAccountDto dto = new CachedAccountDto(account);
            redisTemplate.opsForValue().set(key, dto, ttl);
        } catch (Exception ex) {
            log.warn("Redis error on account cache SET for key {}: {}. Skipping cache population.", key, ex.getMessage());
            recordMetric("redis.cache.errors");
        }
    }

    /**
     * Invalidates (evicts) an account from Redis cache after an authoritative mutation.
     *
     * @param accountId account UUID
     */
    public void evict(UUID accountId) {
        if (redisTemplate == null || accountId == null) {
            return;
        }

        String key = RedisKeyNamespaces.accountCacheKey(accountId);
        try {
            redisTemplate.delete(key);
            recordMetric("redis.cache.invalidations");
        } catch (Exception ex) {
            log.warn("Redis error on account cache EVICT for key {}: {}. DB state remains authoritative.", key, ex.getMessage());
            recordMetric("redis.cache.errors");
        }
    }

    private void recordMetric(String metricName) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(metricName, "cache", "account").increment();
            } catch (Exception ignored) {
            }
        }
    }
}
