# Phase 19 — Performance Engineering Evidence

## Status

**Evidence status:** MEASURED ON REFERENCE TEST ENVIRONMENT

This report preserves the empirically documented Phase 19 performance limits. These are capacity observations, not universal production guarantees.

## Measured Results

- **185 TPS** — sustainable operating ceiling on the reference financial transaction path under 100 VUs steady state.
- **235 TPS** — observed peak test load under a progressive benchmark at 200 VUs.
- **220–240 TPS** — observed physical saturation region at 400 VUs.
- **Preferred Measured Operating Point:** 185 TPS, subject to the environment and workload assumptions documented below.

The measurements cover the full financial path: authentication, idempotency, pessimistic account locking, double-entry ledger posting, transactional outbox persistence, and database commit.

## Evidence Sources

- `docs/performance/BASELINE.md`
- `docs/performance/LOAD-TESTING.md`
- `docs/performance/CAPACITY-MODEL.md`
- `docs/portfolio/PERFORMANCE-SUMMARY.md`

## Limitations

These measurements were performed on a single-node/containerized reference environment. They do not establish multi-node production capacity, multi-hour soak behavior, cloud-specific limits, network variance, managed PostgreSQL behavior, or Kafka cluster scaling characteristics.

Production capacity must therefore be revalidated against the actual deployment topology before setting customer-facing SLO capacity commitments.
