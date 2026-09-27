package com.paymentledger.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying:
 *  - Spring Boot starts successfully with PostgreSQL
 *  - Flyway baseline migration (V1) applies cleanly on startup
 *  - JdbcTemplate bean is injectable (datasource connectivity verified)
 */
class PostgresFlywayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway V1 baseline migration should be applied on a clean PostgreSQL instance")
    void verifyPostgresAndFlywayBaseline() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'",
                Integer.class
        );
        assertThat(count).isNotNull().isEqualTo(1);
    }
}
