package com.paymentledger.messaging.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.messaging.event.EventEnvelope;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private volatile boolean simulateTransientFailure = false;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void setSimulateTransientFailure(boolean simulateTransientFailure) {
        this.simulateTransientFailure = simulateTransientFailure;
    }

    @Override
    public <T> CompletableFuture<SendResult<String, String>> publish(String topic, String partitionKey, EventEnvelope<T> event) {
        if (simulateTransientFailure) {
            CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new org.apache.kafka.common.errors.NetworkException("Simulated Kafka Broker Unavailable (Connection Refused)"));
            return failed;
        }
        try {
            String jsonPayload = objectMapper.writeValueAsString(event);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, jsonPayload);
            if (event.correlationId() != null) {
                record.headers().add(new RecordHeader("X-Correlation-ID", event.correlationId().toString().getBytes(StandardCharsets.UTF_8)));
            }
            if (event.eventId() != null) {
                record.headers().add(new RecordHeader("X-Event-ID", event.eventId().toString().getBytes(StandardCharsets.UTF_8)));
            }
            if (event.eventType() != null) {
                record.headers().add(new RecordHeader("X-Event-Type", event.eventType().getBytes(StandardCharsets.UTF_8)));
            }
            if (event.schemaVersion() != null) {
                record.headers().add(new RecordHeader("X-Schema-Version", event.schemaVersion().getBytes(StandardCharsets.UTF_8)));
            }

            long start = System.currentTimeMillis();
            log.info("Publishing event {} [{}] to topic {} (key={})", event.eventId(), event.eventType(), topic, partitionKey);

            return kafkaTemplate.send(record).whenComplete((result, ex) -> {
                long duration = System.currentTimeMillis() - start;
                if (ex != null) {
                    log.error("Failed to publish event {} [{}] to topic {} after {}ms: {}",
                            event.eventId(), event.eventType(), topic, duration, ex.getMessage(), ex);
                } else {
                    log.info("Successfully published event {} [{}] to topic {} [partition={}, offset={}] in {}ms",
                            event.eventId(), event.eventType(), topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(), duration);
                }
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event envelope {}: {}", event.eventId(), e.getMessage());
            CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }
}
