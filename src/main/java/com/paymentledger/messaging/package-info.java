/**
 * Messaging and Kafka integration module boundary.
 * <p>
 * Responsible for: Standard CloudEvents-inspired event envelopes (v1.0), Kafka producer/consumer
 * templates, partition key routing, schema evolution, dead letter queue (DLQ) routing,
 * and idempotent consumer deduplication. Kafka serves as asynchronous transport, not source of truth.
 * Implementation scheduled for Phase 8.
 */
package com.paymentledger.messaging;
