/**
 * Financial reconciliation module boundary.
 * <p>
 * Responsible for: Provider settlement file ingestion, automated transaction matching,
 * discrepancy categorization (MISSING_INTERNAL, MISSING_PROVIDER, AMOUNT_MISMATCH, etc.),
 * and controlled operator resolution via compensating SYSTEM_ADJUSTMENT ledger transactions.
 * Implementation scheduled for Phase 12.
 */
package com.paymentledger.reconciliation;
