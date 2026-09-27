# ADR-016 — Production Observability Architecture

**Date**: 2026-09-25  
**Status**: Accepted  
**Phase**: 16  
**Authors**: Platform Engineering Team

---

## Context

The Distributed Payment & Ledger Platform (Phases 0–15) required a production-grade observability
layer to enable:
- Early detection of financial integrity violations
- Operational confidence in payment processing
- SLO/SLI measurement and error budget management
- Root cause analysis during incidents

The observability system needed to be designed such that **it MUST NEVER become part of financial
correctness**. A metrics recording failure must not prevent a payment from being processed, a
ledger entry from being posted, or a refund from being settled.

---

## Decision

### 1. Fail-Safe Centralized Metrics Service

**Decision**: Create `PlatformMetrics` as a centralized, fail-safe Micrometer wrapper.

**Rationale**:
- All metric recording is wrapped in `safeExecute(Runnable)` which catches and logs (but never
  rethrows) any exception from the metrics subsystem
- `MeterRegistry` is injected with `@Autowired(required = false)` — null registry simply results
  in no-op (no NPE)
- This makes the observability layer completely decoupled from the financial execution path

**Alternative rejected**: Directly calling Micrometer `Counter.increment()` in services — rejected
because a `Counter` being unregistered or the registry being null would cause NPEs in service code.

### 2. Strict Cardinality Bounds

**Decision**: Block UUIDs, emails, URLs, and strings > 64 chars as metric tag values.

**Rationale**:
- Prometheus/Micrometer store all unique label value combinations in memory
- A single unbounded tag (e.g., `paymentId`) with millions of payments would cause out-of-memory
  on the metrics registry
- Tags are blocked at the `PlatformMetrics.sanitizeBoundedLabel()` layer before registration

**Permitted tags**: `currency`, `type`, `reason`, `result`, `action`, `resource`, `topic`,
`event_type`, `cache`, `operation`, `accountType`

**Prohibited tags**: Any UUID, email, URL, `paymentId`, `userId`, `accountId`, `correlationId`

### 3. MDC-Based Correlation Tracing (Phase 16 Scope)

**Decision**: Use `X-Correlation-ID` + `X-Request-ID` via `CorrelationIdFilter` and SLF4J MDC.

**Rationale**:
- Full distributed tracing (OpenTelemetry/Jaeger/Zipkin) requires additional infrastructure
  (OTLP collector, trace backend) and introduces Kafka consumer span propagation complexity
- MDC-based correlation satisfies the operational traceability requirement for current scale
- The outbox relay propagates `correlationId` from event persistence through Kafka publishing

**Future**: Full OTel-based distributed tracing can be added in a future phase by adding
`micrometer-tracing-bridge-otel` and an OTel agent.

### 4. Logback CompositeConverter for Log Masking

**Decision**: Implement `LogMaskingConverter extends CompositeConverter<ILoggingEvent>` registered
as `%mask(%m)` in `logback-spring.xml`.

**Rationale**:
- `%mask(%m)` uses composite converter syntax (wrapping the `%m` message)
- Logback requires the converter class to extend `ch.qos.logback.core.pattern.CompositeConverter`
  for this pattern — `MessageConverter` cannot be used with composite syntax
- The `transform(ILoggingEvent, String)` abstract method receives the pre-rendered message

**Patterns masked**:
1. Bearer JWT tokens
2. JSON/KV password, secret, token, apiKey, cvv fields
3. Credit card PANs (Visa, Mastercard, Amex, Discover)
4. PEM-format private keys

### 5. Four Grafana Dashboards (Not 12)

**Decision**: Implement 4 purpose-specific dashboards rather than 12 granular dashboards.

**Rationale**:
- The gap analysis planning estimate of "12 dashboards" was aspirational
- 4 dashboards cover all required operational monitoring domains at adequate depth:
  - Platform Overview (all-up status)
  - Payment Domain (financial domain metrics)
  - Ledger Integrity (financial correctness)
  - Infrastructure & JVM (operational health)
- Additional dashboards would be redundant without additional metric families

### 6. Zero-Duration Alerts for Financial Integrity

**Decision**: `LedgerUnbalancedTransaction` and `LedgerInvariantFailure` use `for: 0m`.

**Rationale**:
- These alerts represent financial correctness violations with zero tolerance
- Any transient delay (e.g., `for: 2m`) would suppress a P1 incident for 2 minutes
- Unlike infrastructure alerts (where transient spikes are normal), a single unbalanced
  transaction represents a genuine financial integrity failure requiring immediate action
- `KafkaDltEventsDetected` also uses `for: 0m` because DLT events represent message loss
  which has immediate operational impact

---

## Consequences

### Positive
- Financial operations are fully isolated from observability failures
- Metric cardinality is bounded — no memory leak risk from unbounded labels
- All 22 alerts have runbooks, severity, and anti-flapping durations
- Log masking protects PII and secrets from appearing in logs
- MDC correlation enables end-to-end request tracing through logs

### Negative / Trade-offs
- No distributed traces (no Jaeger/Zipkin) — log correlation only
- `/actuator/prometheus` requires authentication in production (Spring Security)
  — Prometheus scraping needs credentials or network-level access control
- Only 4 dashboards — future operational needs may require additional dashboards

### Risks
- If `PlatformMetrics` is bypassed (e.g., direct counter usage), cardinality protection
  is lost — mitigated by code review + no other counter usage in services
- Logback configuration errors crash the application context on startup
  — mitigated by integration test that starts the Spring context

---

## Related Decisions
- ADR-006: Ledger Financial Source of Truth (Phase 6)
- ADR-009: Transactional Outbox Pattern (Phase 9)
- ADR-015: Comprehensive Testing Strategy (Phase 15)
