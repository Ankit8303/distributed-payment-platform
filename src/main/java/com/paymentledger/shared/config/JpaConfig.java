package com.paymentledger.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA and transaction management baseline configuration.
 * <p>
 * Ensures ACID transactional guarantees across domain operations.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.paymentledger")
public class JpaConfig {
}
