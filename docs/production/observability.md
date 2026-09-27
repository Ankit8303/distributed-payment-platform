# Observability

Use Spring Actuator + Micrometer.

Required:
- health/readiness/liveness;
- request metrics;
- payment success/failure metrics;
- refund metrics;
- ledger posting metrics;
- outbox backlog;
- Kafka consumer lag;
- reconciliation status;
- database pool metrics;
- Redis health;
- structured logs;
- correlation/request IDs.

Never expose secrets or sensitive payment data in telemetry.
