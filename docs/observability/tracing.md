# Tracing & Correlation — Payment & Ledger Platform

> Phase 16 Observability — MDC-based correlation ID propagation.
>
> **Scope**: Phase 16 implements MDC-based correlation tracing using `X-Correlation-ID` and
> `X-Request-ID` headers. Distributed tracing with Jaeger/Zipkin/OpenTelemetry spans is
> **not** part of Phase 16 and is deferred to a future observability phase.

---

## Architecture

Phase 16 uses MDC (Mapped Diagnostic Context) for request correlation:

```
Incoming HTTP Request
     │
     ▼
CorrelationIdFilter
     │  - Reads X-Correlation-ID header (or generates UUID)
     │  - Reads X-Request-ID header (or generates UUID)
     │  - Puts both into SLF4J MDC
     │  - Echoes both as HTTP response headers
     ▼
All log statements during request include:
     [correlationId=abc-123] [requestId=xyz-456]
     │
     ▼
OutboxRelayScheduler
     │  - Reads correlationId from OutboxEventEntity
     │  - Sets correlationId in MDC before publishing
     │  - Clears MDC after each event
     ▼
Kafka Consumer (PaymentEventAuditConsumer)
     │  - Can restore correlationId from EventEnvelope.metadata
```

---

## MDC Fields

| Field | Header | Source | Cleared |
|---|---|---|---|
| `correlationId` | `X-Correlation-ID` | HTTP header or generated UUID | After request in `finally` block |
| `requestId` | `X-Request-ID` | HTTP header or generated UUID | After request in `finally` block |

---

## Log Pattern

```
%d{ISO8601} [%thread] %-5level [%X{correlationId}] [%X{requestId}] %logger{36} - %mask(%msg)%n
```

Example:
```
2026-09-25T12:34:56.789Z [http-nio-8080-exec-1] INFO  [corr-abc-123] [req-xyz-456] c.p.p.s.PaymentService - Payment created
```

---

## Outbox Relay Correlation

When the `OutboxRelayScheduler` processes an event:

1. The `OutboxEventEntity.correlationId` was stored during the original payment transaction
2. The scheduler restores this into MDC before publishing to Kafka
3. Log statements during publish include the original request's `correlationId`
4. This creates a log-level correlation trace from HTTP → DB outbox → Kafka

---

## Future: Distributed Tracing

Full distributed tracing (OpenTelemetry OTLP → Jaeger/Zipkin) is **not** implemented in Phase 16.

To add it, the following would be required:
- Add `io.micrometer:micrometer-tracing-bridge-otel` dependency
- Add `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`
- Configure OTLP exporter endpoint
- Propagate trace/span IDs via W3C `traceparent` header

This is a non-trivial addition with Kafka consumer span propagation complexity.
The MDC correlation approach in Phase 16 provides sufficient operational traceability
for the current platform scale.
