# Structured Logging — Payment & Ledger Platform

> Phase 16 Observability — Structured log format, correlation, masking, and retention.

---

## Log Format

All logs are emitted via **Logback** in a structured pattern containing:

```
%d{ISO8601} [%thread] %-5level [%X{correlationId}] [%X{requestId}] %logger{36} - %msg%n
```

Example output:
```
2026-09-25T12:34:56.789Z [http-nio-8080-exec-1] INFO  [corr-abc-123] [req-xyz-456] c.p.p.s.PaymentService - Payment created: status=AUTHORIZED
```

---

## MDC Context Fields

| Field | Source | Description |
|---|---|---|
| `correlationId` | `X-Correlation-ID` header / generated | Trace across HTTP → Outbox → Kafka |
| `requestId` | `X-Request-ID` header / generated | Per-request unique ID |

These fields are populated by `CorrelationIdFilter` and cleared in the `finally` block after each request.

### Kafka Event Propagation
- `correlationId` is stored on `OutboxEventEntity.correlationId`
- The `OutboxRelayScheduler` reads `correlationId` from the outbox event and sets it in MDC before publishing
- Kafka consumers restore `correlationId` from the event envelope into MDC

---

## PII / Credential Masking

The `LogMaskingConverter` automatically redacts:

| Pattern | Replacement |
|---|---|
| JWT tokens (`eyJ...`) | `[MASKED-JWT]` |
| Credit card PANs (16 digits) | `[MASKED-PAN]` |
| `"password":"..."` | `"password":"[MASKED]"` |
| `"secret":"..."` | `"secret":"[MASKED]"` |
| `"token":"..."` | `"token":"[MASKED]"` |
| `"authorization":"..."` | `"authorization":"[MASKED]"` |
| PEM private keys | `[MASKED-KEY]` |

---

## Log Levels

| Level | Usage |
|---|---|
| `ERROR` | Unrecoverable errors, exceptions that propagate to caller |
| `WARN` | Recoverable issues: metric recording failures, retry attempts, idempotency replays |
| `INFO` | Key business events: payment created, settled, refund initiated, event relayed |
| `DEBUG` | Operational detail: batch sizes, claim counts, individual event processing |
| `TRACE` | Not used in production |

---

## Log Retention

- **Development**: Console only, no persistence
- **Production (recommended)**: Ship to centralized log aggregation (ELK, Loki, Cloud Logging)
  - Minimum 30-day hot retention
  - Minimum 12-month cold/archive retention (financial audit requirement)
  - Logs containing `ERROR` or `WARN` should be indexed for alerting

---

## Sensitive Field Policy

All log messages MUST:
1. Never log raw payment amounts in plaintext (log amounts in minor units only for debugging)
2. Never log account UUIDs at INFO level in payment path (use at DEBUG)
3. Never log JWT token values under any circumstance
4. Never log database passwords, Redis passwords, or API keys

The `LogMaskingConverter` provides defense-in-depth but is not a substitute for
developer discipline in log message construction.
