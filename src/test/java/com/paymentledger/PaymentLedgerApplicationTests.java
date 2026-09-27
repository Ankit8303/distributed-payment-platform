package com.paymentledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot application context load test.
 * Verifies that the complete application context loads successfully with PostgreSQL,
 * Flyway migration, Kafka, and Redis infrastructure configuration.
 */
import com.paymentledger.infrastructure.AbstractIntegrationTest;

class PaymentLedgerApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("Spring Boot application context should load cleanly with PostgreSQL, Flyway, Kafka, and Redis")
    void contextLoads() {
    }
}
