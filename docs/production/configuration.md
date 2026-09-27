# Production Configuration Reference

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  

---

## 1. Profiles & Configuration Hierarchy

The platform defines three primary configuration profiles:
1. `dev`: Local development against local Docker Compose (SQL logging enabled, debug levels, local Redis/Kafka).
2. `test`: Isolated integration testing with Testcontainers, embedded Kafka or test container mocks, dynamic port binding.
3. `prod`: Production deployment. Enforces strict logging, bounded connection pools, graceful shutdown, and mandatory environment variable injection for credentials.

Profile selection is controlled via:
```bash
export SPRING_PROFILES_ACTIVE=prod
```

---

## 2. Mandatory Environment Variables (Production)

The production configuration (`application-prod.yml`) will fail fast if any of the following variables are missing:

| Variable | Description | Example / Required Format |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC Connection URL | `jdbc:postgresql://postgres.internal:5432/payment_ledger?sslmode=verify-full` |
| `DB_USERNAME` | Database username | `payment_ledger_app` |
| `DB_PASSWORD` | Database password | Minimum 24 characters strong secret |
| `JWT_SECRET` | 256-bit secret key for HMAC-SHA256 | Minimum 32 characters high-entropy string |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker endpoints | `kafka-1.internal:9092,kafka-2.internal:9092` |
| `REDIS_HOST` | Redis host for auxiliary caching & rate limits | `redis.internal` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis authentication password | Strong password |

---

## 3. Connection Pool Configuration (HikariCP)

| Property | Production Value | Rationale |
|---|---|---|
| `maximum-pool-size` | `20` (configurable via `HIKARI_MAX_POOL_SIZE`) | Sized to prevent database thread saturation while handling burst concurrency. |
| `minimum-idle` | `10` | Keeps warm connections ready to avoid connection handshake spikes. |
| `connection-timeout` | `30000ms` (30s) | Prevents thread starvation if DB is temporarily saturated. |
| `idle-timeout` | `600000ms` (10m) | Retires idle connections safely. |
| `max-lifetime` | `1800000ms` (30m) | Prevents stale connections and firewall NAT drops. |

---

## 4. Kafka & Messaging Production Configuration

- `producer.acks`: `all` (ensures durability across all in-sync replicas).
- `producer.retries`: `3` with exponential backoff.
- `producer.properties.enable.idempotence`: `true` (eliminates duplicate broker publishes).
- `consumer.max-poll-records`: `50` (prevents consumer group rebalance timeouts during slow batches).
- `consumer.auto-offset-reset`: `earliest` (no event loss on new consumer groups).

---

## 5. Actuator & Operational Endpoints

In production, only the minimum required operational endpoints are exposed:
- `/actuator/health`: Public probe, `show-details: when_authorized`.
- `/actuator/health/liveness`: Public container probe.
- `/actuator/health/readiness`: Public load-balancer probe verifying DB connectivity.
- `/actuator/info`: Public version information.
- `/actuator/metrics`: Protected, requires `ROLE_ADMIN`.
- `/actuator/prometheus`: Protected, requires `ROLE_ADMIN` scraper credentials.
- All sensitive actuator endpoints (`env`, `beans`, `configprops`, `heapdump`, `threaddump`) are disabled.
