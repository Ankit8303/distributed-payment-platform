/**
 * Transactional outbox module boundary.
 * <p>
 * Responsible for: Atomic persistence of domain events within the same database transaction
 * as state changes, outbox polling publisher using SKIP LOCKED, Kafka publishing guarantees,
 * backoff retries, and event status lifecycle (PENDING, PUBLISHED, FAILED).
 * Implementation scheduled for Phase 9.
 */
package com.paymentledger.outbox;
