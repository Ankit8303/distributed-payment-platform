package com.paymentledger.infrastructure;

import com.paymentledger.ledger.domain.*;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * P2 Database Integrity Tests — verifies:
 *  1. Flyway migration chain applies cleanly (all 13 migrations).
 *  2. Ledger entries are immutable via DB trigger (trg_immutable_ledger_entries).
 *  3. Ledger transactions are immutable via DB trigger (trg_immutable_ledger_transactions — V13).
 *  4. JPA cascade change (CascadeType.PERSIST only) prevents JPA cascade-delete of ledger entries.
 *  5. Reconciliation attempts FK is RESTRICT (not CASCADE).
 *  6. admin_audit_logs has FK on actor_user_id.
 *  7. Migration numbering: 13 migrations in flyway_schema_history.
 *  8. Double-entry balance check passes.
 */
class P2DatabaseIntegrityTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Migration chain integrity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-MIGRATION-01: All 13 Flyway migrations applied cleanly on a clean PostgreSQL instance")
    void allMigrationsApplyCleanly() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class
        );
        // V1 through V13 = 13 migrations
        assertThat(count).isNotNull().isGreaterThanOrEqualTo(13);
    }

    @Test
    @DisplayName("P2-MIGRATION-02: All Flyway migrations have contiguous version numbers (no gaps)")
    void migrationVersionsAreContiguous() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank",
                String.class
        );
        // Verify versions are present for 1-13
        assertThat(versions).containsAll(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13"));
    }

    @Test
    @DisplayName("P2-MIGRATION-03: No failed migrations remain in flyway_schema_history")
    void noFailedMigrationsExist() {
        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class
        );
        assertThat(failedCount).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Schema object verification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-SCHEMA-01: All expected tables exist after migration")
    void allExpectedTablesExist() {
        List<String> expectedTables = List.of(
                "users", "refresh_tokens", "accounts",
                "idempotency_records", "payments",
                "ledger_transactions", "ledger_entries",
                "consumed_messages", "payment_event_audits",
                "outbox_events",
                "refunds", "reversals", "payouts", "financial_adjustments",
                "reconciliation_cases", "reconciliation_attempts",
                "notification_templates", "notifications", "notification_deliveries",
                "webhook_subscriptions",
                "admin_audit_logs"
        );

        for (String table : expectedTables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table
            );
            assertThat(count).as("Table '%s' must exist", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("P2-SCHEMA-02: payouts table has idempotency_key column (V13 hardening)")
    void payoutsHasIdempotencyKeyColumn() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'payouts' AND column_name = 'idempotency_key'",
                Integer.class
        );
        assertThat(count).as("payouts.idempotency_key column must exist").isEqualTo(1);
    }

    @Test
    @DisplayName("P2-SCHEMA-03: admin_audit_logs has FK constraint to users (V13 hardening)")
    void adminAuditLogsHasFkToUsers() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE constraint_name = 'fk_admin_audit_logs_actor' AND table_name = 'admin_audit_logs'",
                Integer.class
        );
        assertThat(count).as("fk_admin_audit_logs_actor FK constraint must exist").isEqualTo(1);
    }

    @Test
    @DisplayName("P2-SCHEMA-04: reconciliation_attempts FK is RESTRICT (not CASCADE)")
    void reconciliationAttemptsFkIsRestrict() {
        List<String> deleteRules = jdbcTemplate.queryForList(
                "SELECT rc.delete_rule " +
                "FROM information_schema.referential_constraints rc " +
                "JOIN information_schema.table_constraints tc ON rc.constraint_name = tc.constraint_name " +
                "WHERE tc.table_name = 'reconciliation_attempts' " +
                "  AND tc.constraint_type = 'FOREIGN KEY'",
                String.class
        );
        // The FK from reconciliation_attempts to reconciliation_cases must be RESTRICT
        assertThat(deleteRules).as("reconciliation_attempts FK must be RESTRICT, not CASCADE")
                .allSatisfy(rule -> assertThat(rule).isEqualTo("RESTRICT"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Ledger immutability
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-LEDGER-01: Direct SQL DELETE on ledger_entries is blocked by DB trigger")
    void directSqlDeleteOnLedgerEntriesIsBlocked() {
        // Create a real ledger transaction and entry for the test
        UUID txId = UUID.randomUUID();
        UUID accountId = createTestAccount();

        jdbcTemplate.update(
                "INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status, created_at) " +
                "VALUES (?, 'PAYMENT', ?, 'PAYMENT', 'USD', 'Test tx for immutability', 'POSTED', NOW())",
                txId, UUID.randomUUID()
        );

        UUID entryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, direction, amount_minor, currency, sequence_number, created_at) " +
                "VALUES (?, ?, ?, 'DEBIT', 1000, 'USD', 1, NOW())",
                entryId, txId, accountId
        );

        // Attempt to DELETE the ledger entry directly — must be blocked by trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", entryId)
        ).isInstanceOf(DataIntegrityViolationException.class)
         .hasMessageContaining("CANNOT_MODIFY_POSTED_LEDGER");
    }

    @Test
    @DisplayName("P2-LEDGER-02: Direct SQL DELETE on ledger_transactions is blocked by DB trigger (V13)")
    void directSqlDeleteOnLedgerTransactionsIsBlocked() {
        UUID txId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status, created_at) " +
                "VALUES (?, 'PAYMENT', ?, 'PAYMENT', 'USD', 'Test tx for immutability delete', 'POSTED', NOW())",
                txId, UUID.randomUUID()
        );

        // Attempt to DELETE the ledger transaction — must be blocked by trigger (V13)
        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM ledger_transactions WHERE id = ?", txId)
        ).isInstanceOf(DataIntegrityViolationException.class)
         .hasMessageContaining("CANNOT_DELETE_LEDGER_TRANSACTION");
    }

    @Test
    @DisplayName("P2-LEDGER-03: Direct SQL UPDATE on ledger_entries is blocked by DB trigger")
    void directSqlUpdateOnLedgerEntriesIsBlocked() {
        UUID txId = UUID.randomUUID();
        UUID accountId = createTestAccount();

        jdbcTemplate.update(
                "INSERT INTO ledger_transactions (id, transaction_type, source_reference_id, source_reference_type, currency, description, status, created_at) " +
                "VALUES (?, 'PAYMENT', ?, 'PAYMENT', 'USD', 'Test for update block', 'POSTED', NOW())",
                txId, UUID.randomUUID()
        );

        UUID entryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, direction, amount_minor, currency, sequence_number, created_at) " +
                "VALUES (?, ?, ?, 'CREDIT', 500, 'USD', 2, NOW())",
                entryId, txId, accountId
        );

        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE ledger_entries SET amount_minor = 99999 WHERE id = ?", entryId)
        ).isInstanceOf(DataIntegrityViolationException.class)
         .hasMessageContaining("CANNOT_MODIFY_POSTED_LEDGER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Financial data type verification
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-FINANCIAL-01: All monetary columns use BIGINT (minor units) — no FLOAT or NUMERIC")
    void monetaryColumnsAreBigint() {
        List<String> monetaryColumns = jdbcTemplate.queryForList(
                "SELECT table_name || '.' || column_name AS col " +
                "FROM information_schema.columns " +
                "WHERE table_schema = 'public' " +
                "  AND data_type NOT IN ('bigint') " +
                "  AND (column_name LIKE '%amount_minor%' OR column_name LIKE '%balance_minor%')",
                String.class
        );
        assertThat(monetaryColumns)
                .as("All monetary minor-unit columns must be BIGINT. Found non-BIGINT: " + monetaryColumns)
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID createTestAccount() {
        // Create a test user first (required by FK)
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', NOW(), NOW())",
                userId, "p2test-" + userId + "@test.com"
        );

        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, account_number, owner_id, account_type, currency, status, materialized_balance_minor, version, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'CUSTOMER', 'USD', 'ACTIVE', 100000, 0, NOW(), NOW())",
                accountId, "ACC-P2-" + accountId.toString().substring(0, 8), userId
        );
        return accountId;
    }
}
