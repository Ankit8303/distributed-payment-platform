/**
 * Payment processing module boundary.
 * <p>
 * Responsible for: Payment lifecycle, payment state machine (PENDING, AUTHORIZED, SETTLED,
 * PENDING_RECONCILIATION, FAILED), provider adapter boundaries, durable idempotency enforcement,
 * and atomic coordination with double-entry ledger posting and transactional outbox.
 * Implementation scheduled for Phase 5.
 */
package com.paymentledger.payment;
