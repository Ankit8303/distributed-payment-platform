package com.paymentledger.messaging.producer;

import com.paymentledger.messaging.event.EventEnvelope;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

/**
 * Domain-facing event publisher interface decoupling domain code from Kafka transport.
 */
public interface EventPublisher {

    <T> CompletableFuture<SendResult<String, String>> publish(String topic, String partitionKey, EventEnvelope<T> event);
}
