# Production Graceful Shutdown Specification

**Document Version**: 1.0  
**Phase**: Phase 17 — Production Hardening  

---

## 1. Overview & Invariant

During container termination, application redeployment, or host maintenance, the platform must shut down gracefully without:
- Dropping active in-flight payments
- Leaving partial ledger transactions
- Abandoning outbox publishing batches
- Leaking open database, Redis, or Kafka connections

Financial transactions must either commit completely (both ledger entries and outbox events saved) or rollback entirely.

---

## 2. Shutdown Sequence & Phases

When a `SIGTERM` signal is received by the container process:

1. **Phase 1: Ingress Severance & Probe Transition (T + 0s)**
   - Spring Boot Actuator `/actuator/health/readiness` transitions to `OUT_OF_SERVICE`.
   - Upstream load balancers and container orchestrators stop routing new incoming traffic to the container.

2. **Phase 2: Active Request Draining (`server.shutdown: graceful`) (T + 0s to T + 20s)**
   - The embedded web server stops accepting new connections on port 8080.
   - In-flight HTTP requests (payment authorizations, captures, queries) are given up to 20 seconds to complete execution and return responses to callers.

3. **Phase 3: Background Worker Suspension (T + 20s)**
   - Scheduled workers (`ReconciliationWorker`, `NotificationWorker`, `OutboxRelayScheduler`) stop polling for new batches.
   - Any currently active batch completes its atomic database transaction before worker thread termination.

4. **Phase 4: Kafka Producer & Consumer Teardown (T + 22s)**
   - Kafka listener containers close poll loops and commit consumer offsets for processed records.
   - Kafka producers flush remaining buffered records (`kafkaTemplate.flush()`).

5. **Phase 5: Resource Pool Disposal (T + 25s)**
   - Redis connection pools terminate open sockets cleanly.
   - HikariCP connection pool closes idle connections and waits for active transactions to complete before closing physical TCP sockets.
   - Application context terminates cleanly with exit code 0.

---

## 3. Configuration Parameters

In `application.yml` and `application-prod.yml`:
```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
  task:
    scheduling:
      shutdown:
        await-termination: true
        await-termination-period: 20s
    execution:
      shutdown:
        await-termination: true
        await-termination-period: 20s
```
Container orchestrators should configure termination grace periods of at least `30s` (`terminationGracePeriodSeconds: 30`) to accommodate the 20-second internal shutdown phase.
