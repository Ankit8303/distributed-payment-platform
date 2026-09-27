package com.paymentledger.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying Redis and Kafka infrastructure beans are registered in context.
 */
class RedisAndKafkaIntegrationTest extends AbstractIntegrationTest {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Redis and Kafka infrastructure bean templates should be registered in the application context")
    void verifyRedisAndKafkaBeans() {
        assertThat(redisTemplate).as("RedisTemplate should be registered as Spring bean").isNotNull();
        assertThat(kafkaTemplate).as("KafkaTemplate should be registered as Spring bean").isNotNull();
    }
}
