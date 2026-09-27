/**
 * Customer and merchant account module boundary.
 * <p>
 * Responsible for: Account lifecycle, account types (CUSTOMER, MERCHANT, INTERNAL_SETTLEMENT, FEES, ESCROW),
 * account freezing/unfreezing, materialized balance cache updates, and account-level concurrency locking.
 * Implementation scheduled for Phase 4.
 */
package com.paymentledger.account;
