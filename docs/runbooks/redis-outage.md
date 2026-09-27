# Operational Runbook: Redis Outage & Auxiliary Cache Fallback

**Severity:** SEV-2 / SEV-3  
**Target Subsystem:** Redis Cluster / Distributed Rate Limiter & Auxiliary Cache

---

## 1. Symptoms
- Application logs showing `RedisConnectionFailureException` or `QueryTimeoutException`.
- Increased rate limiting warnings in application logs.
- Client requests potentially experiencing minor latency increase during connection retry backoff.

## 2. Detection
- Alert: `RedisUnavailable` or `RedisConnectionErrorsSpike`.
- Metric: `redis_errors > 0`.
- Actuator health reporting Redis component `DOWN`.

## 3. Diagnosis
- Check Redis server status: `redis-cli ping`.
- Check Redis memory usage: `redis-cli info memory`.
- Review Redis slowlog: `redis-cli slowlog get 10`.

## 4. Commands
```bash
# Verify Redis connectivity
redis-cli -h $REDIS_HOST -p $REDIS_PORT ping

# Inspect connected clients and memory
redis-cli -h $REDIS_HOST -p $REDIS_PORT info clients
redis-cli -h $REDIS_HOST -p $REDIS_PORT info memory
```

## 5. Safe Actions
- The platform is engineered with fail-safe defaults: Redis is NOT an authoritative source of financial data.
- Rate limiting automatically falls back to in-memory fallback or permits traffic in degraded mode to avoid blocking valid financial flows.
- Restart Redis server or trigger AWS ElastiCache / Redis Sentinel failover to standby replica.

## 6. Unsafe Actions
- **NEVER** store authoritative ledger balances or transaction states in Redis.
- **NEVER** run `FLUSHALL` or `FLUSHDB` indiscriminately if active idempotency reservation caches are warm.

## 7. Rollback
- If a new application version introduced high Redis connection churn, roll back the deployment.

## 8. Verification
- Verify `redis-cli ping` returns `PONG`.
- Verify `/actuator/health` shows Redis health status `UP`.
- Check that application log errors for Redis cease.

## 9. Post-Incident Checks
- Verify rate limiting keys re-populate cleanly.
- Verify no duplicate ledger transactions were created during the Redis degraded window.
