/**
 * Double-entry financial ledger module boundary.
 * <p>
 * Responsible for: Immutable double-entry ledger transactions, ledger entries (DEBIT/CREDIT),
 * 64-bit integer minor unit Money value object, zero-sum balancing invariant enforcement,
 * sequence number monotonic ordering, and authoritative balance computation.
 * PostgreSQL is the sole financial source of truth.
 * Implementation scheduled for Phase 6.
 */
package com.paymentledger.ledger;
