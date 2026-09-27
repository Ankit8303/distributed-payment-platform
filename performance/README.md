# Performance and Load Testing Framework

This directory houses the performance and load testing suite for the Distributed Payment & Ledger Platform, standardized on [k6](https://k6.io/).

## Directory Structure

```text
performance/
├── README.md                  # Overview and execution guidelines
├── scenarios/                 # Modular k6 test scenarios
│   ├── health.js              # Health & Actuator liveness/readiness probes
│   ├── authentication.js      # JWT login & token refresh benchmarking
│   ├── account.js             # Account read & balance query benchmarking
│   ├── payment.js             # Full financial payment path under progressive concurrency
│   ├── refund.js              # Compensating refund creation & ledger verification
│   ├── payout.js              # Merchant payout processing & balance locking
│   └── admin.js               # Bounded admin investigation & transaction search
├── datasets/                  # Synthetic test data (strictly no production data)
│   ├── synthetic-users.json   # Test user credentials and roles
│   ├── synthetic-accounts.json# Synthetic account mappings
│   └── payment-payloads.json  # Pre-generated valid financial transaction payloads
├── thresholds/                # Performance budgets and SLO thresholds
│   └── slo-thresholds.json    # Latency (p50, p95, p99) and error rate budgets
└── results/                   # Benchmark summary reports and execution logs
    └── README.md              # Result recording guidelines
```

## Quick Start

### 1. Prerequisites
- Docker & Docker Compose (`docker-compose up -d`)
- Spring Boot Application running on `http://localhost:8080`
- k6 CLI installed (`k6` version >= 0.48.0)

### 2. Running Scenarios

```bash
# Health check baseline
k6 run performance/scenarios/health.js

# Authentication load test
k6 run performance/scenarios/authentication.js

# Critical financial payment path
k6 run performance/scenarios/payment.js

# Full test suite execution via runner scripts
./scripts/performance/run-load.sh
```

## Financial Correctness Guarantee

All financial load tests preserve:
1. `SUM(debits) == SUM(credits)` across all ledger postings.
2. Deterministic lock ordering on accounts to eliminate deadlocks.
3. Strict idempotency enforcement: duplicate requests produce HTTP 409 or return the original outcome with zero duplicate ledger entries.
