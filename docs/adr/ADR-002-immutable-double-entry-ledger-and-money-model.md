# ADR-002: Immutable Double-Entry Ledger and Money Representation

## Status
Accepted

## Context
Financial software requires exact arithmetic, complete traceability, auditability, and mathematical proof that funds do not appear or disappear without balanced counterparties. Floating-point numbers (`float`, `double`) introduce catastrophic precision and rounding errors in monetary computation. Furthermore, mutable balance fields (`UPDATE accounts SET balance = balance + 100`) make it impossible to audit how a balance was reached, obscure race conditions, and violate accounting principles.

## Decision
1. **Money Representation**:
   - Represent Money as an immutable Value Object comprising a 64-bit integer (`long` / `BIGINT`) minor units (e.g., cents, pence, satoshis) and an ISO 4217 three-letter currency code (e.g., `USD`, `EUR`, `GBP`).
   - Floating-point calculations are strictly prohibited anywhere in the monetary domain or database.
   - Division (e.g. fees) must explicitly declare rounding mode and assign remainders deterministically.
2. **Double-Entry Bookkeeping**:
   - Every financial transaction consists of a `LedgerTransaction` containing at least two `LedgerEntry` records.
   - Strict invariant: $\sum \text{Debits} == \sum \text{Credits}$ for every posted transaction.
   - All entries within a single transaction must share the exact same currency.
3. **Immutable Accounting Facts**:
   - Once posted, `ledger_transactions` and `ledger_entries` are immutable. `UPDATE` and `DELETE` operations are strictly forbidden via application logic and database triggers/permissions.
   - Corrections, refunds, and adjustments are handled exclusively through compensating journal entries.
4. **Authoritative vs Materialized Balance**:
   - The authoritative source of truth for an account balance is the mathematical aggregation of all posted immutable ledger entries:
     $$\text{Authoritative Balance} = \sum \text{Credits} - \sum \text{Debits}$$ (for credit-normal accounts, or vice-versa for debit-normal).
   - Any cached or materialized balance (e.g. `accounts.current_balance`) is strictly an operational optimization. In any dispute or integrity audit, the immutable ledger entry history supersedes materialized fields.

## Alternatives Considered
1. **Single-Entry Mutable Balances (`UPDATE accounts SET balance = ...`)**:
   - *Rejected*: Inability to audit discrepancies, loss of transaction history on row mutation, vulnerable to silent balance corruption during concurrency bugs.
2. **`BigDecimal` with Decimal SQL Columns (`NUMERIC(19,4)`)**:
   - *Rejected for core arithmetic*: While `BigDecimal` avoids binary floating-point drift, integer minor units eliminate all scale ambiguities, simplify serialization across JSON/Kafka, and map cleanly to standard 64-bit database integers.

## Consequences
- **Positive**:
  - Zero rounding drift or IEEE-754 precision loss.
  - Complete mathematical provability of financial consistency.
  - Comprehensive, tamper-evident audit trail for regulatory and forensic examination.
- **Negative / Trade-offs**:
  - Calculating balances across millions of historical entries requires snapshotting or materialized balance maintenance with periodic reconciliation.
  - Multi-currency conversions require explicit exchange-rate intermediary settlement accounts.

## Validation
- Automated unit tests asserting that $\sum \text{Debits} \ne \sum \text{Credits}$ throws an un-bypassable domain exception.
- Database trigger / check test ensuring that attempting an `UPDATE` or `DELETE` on a posted ledger entry fails immediately.
