# Incident Response & Financial Discrepancy Protocol

**Repository:** `Ankit8303/distributed-payment-platform`  
**Classification:** Financial Ledger Critical Operations  
**Standard:** ITIL / SRE Incident Management Standard

---

## 1. Severity Classification Matrix

| Level | Severity Name | Impact Criteria | Initial Response SLA | Escalation Target | Communication Cadence |
| :---: | :--- | :--- | :---: | :--- | :--- |
| **SEV-1** | **Critical Financial / System Outage** | Ledger imbalance ($\sum \text{Dr} \ne \sum \text{Cr}$), double settlement detected, primary database outage, security breach/unauthorized fund movement. | **< 15 minutes** | VP Engineering, Principal Architect, Lead Financial Engineer | Every 30 minutes |
| **SEV-2** | **Major Degradation** | External provider outage (> 50% failure rate), reconciliation backlog spike (> 1,000 pending), Redis rate-limit failure with degraded fallback. | **< 30 minutes** | Staff Software Engineer, Lead DevOps | Every 60 minutes |
| **SEV-3** | **Moderate Operational Issue** | Isolated webhook dispatch failures, elevated latency (p95 > 2s), sporadic single-account lock timeouts without data corruption. | **< 2 hours** | On-Call Backend Engineer | Daily / Ticket updates |
| **SEV-4** | **Minor / Cosmetic** | Non-financial logging anomalies, slow admin query report generation, internal non-customer impacting metric glitches. | **< 24 hours** | Engineering Team Backlog | Standard sprint cycle |

---

## 2. Financial Discrepancy Protocol (SEV-1 Procedure)

When an automated consistency auditor or customer report signals a potential ledger discrepancy, the incident team must strictly adhere to the following sequence:

```text
               [ ALERT: Financial Discrepancy Detected ]
                                   │
                                   ▼
          Step 1: HALT RISKY OPERATIONS (Freeze affected accounts/routes)
                                   │
                                   ▼
          Step 2: PRESERVE EVIDENCE (Snapshot DB state, save logs & traces)
                                   │
                                   ▼
          Step 3: IDENTIFY AFFECTED TRANSACTIONS (Query transaction IDs)
                                   │
                                   ▼
          Step 4: RECONCILE DATA LAYERS:
                  ├─ Authoritative Ledger Entries (Dr vs Cr)
                  ├─ Materialized Account Balances
                  ├─ Active Balance Reservations
                  ├─ External Provider Records / Rail Statements
                  ├─ Outbox Events & Kafka Offsets
                  └─ Reconciliation Attempt Logs
                                   │
                                   ▼
          Step 5: DETERMINE AUTHORITATIVE TRUTH (Identify true financial delta)
                                   │
                                   ▼
          Step 6: EXECUTE CONTROLLED CORRECTION VIA DOUBLE-ENTRY ADJUSTMENT
                  (NEVER issue raw SQL UPDATE on balances!)
                                   │
                                   ▼
          Step 7: AUDIT & POST-INCIDENT VERIFICATION
```

### Cardinal Rules of Financial Recovery:
1. **NEVER manually run direct database `UPDATE accounts SET balance = ...`**:
   Directly updating account balances destroys the cryptographic and historical validity of the ledger. Materialized balances must match the sum of ledger entries.
2. **Execute all corrections via `AdminAdjustmentController` / `AdjustmentService`**:
   Adjustments must create balanced, immutable double-entry ledger entries referencing an audit ticket and signed by an authorized administrator (`ROLE_ADMIN`).
3. **Preserve Database Binlogs and Audit Logs**:
   Export audit trails and execution traces before performing any recovery actions.

---

## 3. Incident Command Structure & Roles

- **Incident Commander (IC):** Drives mitigation, assigns tasks, enforces communication.
- **Financial Investigator:** Staff/Principal engineer analyzing ledger entries, reservation states, and database logs.
- **Provider Liaison:** Coordinates with external banking/payment gateway support to verify gateway-side settlement statuses.
- **Communications Lead:** Updates internal stakeholders, executive management, and affected customers.

---

## 4. Post-Incident Review (PIR) & Blameless Post-Mortem

Within 48 hours of resolving any SEV-1 or SEV-2 incident, a formal Post-Incident Review must be produced containing:
1. Executive Summary & Root Cause (5 Whys Analysis).
2. Detailed Timeline of events (T0 detection to recovery).
3. Financial impact (quantified monetary variance, if any).
4. Permanent remediation tasks (code fixes, automated regression tests, architectural guards).
