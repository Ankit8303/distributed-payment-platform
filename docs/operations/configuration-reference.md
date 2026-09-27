# Production Configuration & Environment Reference

**Governing Phase**: Phase 20 — Complete Production-Readiness Verification  
**Target Profile**: `application-prod.yml`  
**Classification**: Operational Configuration Standard  

---

## 1. Overview & Policy

All runtime configurations for the Distributed Payment & Ledger Platform adhere to the Twelve-Factor App methodology:
1. **Strictly Externalized Secrets**: Passwords, encryption keys, and private tokens must never be committed to source code or embedded in Docker images.
2. **Fail-Fast on Missing Secrets**: The application will fail startup immediately if required credentials (`DB_PASSWORD`, `JWT_SECRET`) are undefined.
3. **Safe Production Defaults**: SQL query logging is disabled (`show-sql: false`), DDL auto-generation is disabled (`ddl-auto: validate`), and actuator exposure is restricted to non-sensitive operational endpoints (`health,info,metrics,prometheus`).

---

## 2. Environment Variable Matrix

| Environment Variable | Description | Default (Local / Dev) | Production Requirement | Sensitive? |
|---|---|---|---|:---:|
| `SERVER_PORT` | HTTP port for incoming application traffic | `8080` | `8080` (or container port mapping) | No |
| `DB_URL` | JDBC URL for PostgreSQL database | `jdbc:postgresql://localhost:5432/payment_ledger` | `jdbc:postgresql://<db-host>:5432/<db-name>?sslmode=verify-full` | No |
| `DB_USERNAME` | PostgreSQL service user | `postgres` | Least-privilege application user | No |
| `DB_PASSWORD` | PostgreSQL service user password | `postgres` | **REQUIRED** (High entropy, KMS-injected) | **YES** |
| `HIKARI_MAX_POOL_SIZE` | Maximum database connection pool size | `20` | `20` (Calibrated in Phase 19) | No |
| `HIKARI_MIN_IDLE` | Minimum idle database connections | `10` | `10` | No |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka cluster bootstrap connection string | `localhost:9092` | `broker1:9092,broker2:9092` | No |
| `REDIS_HOST` | Redis cache and rate limiter host | `localhost` | Primary Redis host / cluster endpoint | No |
| `REDIS_PORT` | Redis TCP port | `6379` | `6379` | No |
| `REDIS_PASSWORD` | Redis authentication password | `""` (Empty in dev) | **REQUIRED** if Redis AUTH enabled | **YES** |
| `JWT_SECRET` | Secret key for HMAC-SHA256 JWT signing | Hardcoded dummy in dev | **REQUIRED** (Min 256 bits, base64 encoded) | **YES** |
| `JWT_ACCESS_TOKEN_EXPIRATION_MS` | Access token lifespan in milliseconds | `900000` (15 mins) | `900000` (15 minutes) | No |
| `JWT_REFRESH_TOKEN_EXPIRATION_MS` | Refresh token lifespan in milliseconds | `604800000` (7 days) | `604800000` (7 days) | No |
| `AUTH_LOGIN_RATE_LIMIT` | Maximum login attempts per client per window | `5` | `5` attempts / minute | No |

---

### Redis transport and authentication security

Production Redis connections must use TLS with certificate verification. Redis authentication must be enabled using the deployment environment's approved password or ACL/workload-identity mechanism.

The production environment must provide:
- `REDIS_HOST` and `REDIS_PORT` for the private Redis endpoint;
- `REDIS_PASSWORD` or the approved equivalent authentication mechanism;
- trusted CA/certificate material required to validate the Redis server certificate.

Authentication credentials, private keys, and trust material must not be committed to the repository or baked into the application image.

Plaintext Redis connections are prohibited for production. TLS and authentication are target-environment acceptance controls: the release process must verify the actual Redis endpoint, certificate chain, and authentication/authorization behavior before unrestricted production traffic.

## 3. Production Configuration Safeguards (`application-prod.yml`)

### PostgreSQL transport security

The production `DB_URL` must use PostgreSQL TLS with certificate and hostname verification:

```
jdbc:postgresql://<db-host>:5432/<db-name>?sslmode=verify-full
```

Production database connections must not use `sslmode=disable`. The deployment environment must provide the trusted CA/certificate material required for verification; trust material must not be committed to the repository or baked into the application image.

TLS verification is an environment acceptance gate: the release process must verify the actual database endpoint, certificate chain, and hostname before unrestricted production traffic.

## 3. Production Configuration Safeguards (`application-prod.yml`)

1. **JPA & Hibernate Safeguards**:
   - `spring.jpa.show-sql: false`: Prevents query parameter leakage to stdout.
   - `spring.jpa.hibernate.ddl-auto: validate`: Prevents Hibernate from altering production database tables at startup.
   - `spring.jpa.open-in-view: false`: Prevents lazy-loading queries outside transaction boundaries.
2. **Graceful Shutdown**:
   - `server.shutdown: graceful`: Stops accepting new HTTP requests and allows active requests 20 seconds to drain.
   - `spring.lifecycle.timeout-per-shutdown-phase: 20s`: Closes scheduled workers and outbox relays cleanly.
3. **Actuator & Security Safeguards**:
   - `management.endpoints.web.exposure.include: health,info,metrics,prometheus`: Sensitive endpoints (`env`, `beans`, `heapdump`, `shutdown`) are **disabled**.
   - `management.endpoint.health.show-details: when_authorized`: Hides internal database and broker details from unauthenticated users.
4. **Tomcat Request Bounding**:
   - `server.tomcat.max-http-form-post-size: 2MB`: Blocks oversized POST payloads.
   - `spring.servlet.multipart.enabled: false`: Disables file uploads to eliminate upload attack surfaces.
