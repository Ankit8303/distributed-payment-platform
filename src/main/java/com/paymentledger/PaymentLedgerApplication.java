package com.paymentledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Distributed Payment & Ledger Platform.
 * <p>
 * Architectural style: Modular Monolith with Event-Driven Integration.
 * Technology stack: Java 21, Spring Boot 3.x, PostgreSQL, Kafka, Redis.
 */
@SpringBootApplication
@EnableScheduling
public class PaymentLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentLedgerApplication.class, args);
    }
}
