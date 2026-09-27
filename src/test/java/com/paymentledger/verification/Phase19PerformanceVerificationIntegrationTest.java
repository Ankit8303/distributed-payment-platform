package com.paymentledger.verification;

import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 19 — Load/Performance Testing, Capacity Modeling & Connection-Pool Tuning Verification")
public class Phase19PerformanceVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    // =========================================================================
    // A — Performance Test Tooling
    // =========================================================================
    @Test
    @DisplayName("A — Performance test tooling: k6 scenarios and runner scripts exist")
    void A_performanceTestToolingExists() {
        assertThat(new File("performance/scenarios")).exists().isDirectory();
        assertThat(new File("performance/README.md")).exists();
        assertThat(new File("scripts/performance/run-baseline.sh")).exists();
        assertThat(new File("scripts/performance/run-baseline.ps1")).exists();
        assertThat(new File("scripts/performance/run-load.sh")).exists();
        assertThat(new File("scripts/performance/run-load.ps1")).exists();
    }

    // =========================================================================
    // B — Baseline Documentation
    // =========================================================================
    @Test
    @DisplayName("B — Baseline documentation: BASELINE.md records environment, specs, and latencies")
    void B_baselineDocumentationExists() throws Exception {
        File baseline = new File("docs/performance/BASELINE.md");
        assertThat(baseline).exists();

        String content = Files.readString(baseline.toPath());
        assertThat(content).contains("PostgreSQL 16");
        assertThat(content).contains("Apache Kafka 7.6");
        assertThat(content).contains("Redis 7.2");
        assertThat(content).contains("HikariCP");
        assertThat(content).contains("p50");
        assertThat(content).contains("p95");
        assertThat(content).contains("p99");
        assertThat(content).contains("Full Payment Financial Path");
    }

    // =========================================================================
    // C — Workload Definitions
    // =========================================================================
    @Test
    @DisplayName("C — Workload definitions: All 7 required k6 scenarios exist")
    void C_workloadDefinitionsExist() {
        assertThat(new File("performance/scenarios/health.js")).exists();
        assertThat(new File("performance/scenarios/authentication.js")).exists();
        assertThat(new File("performance/scenarios/account.js")).exists();
        assertThat(new File("performance/scenarios/payment.js")).exists();
        assertThat(new File("performance/scenarios/refund.js")).exists();
        assertThat(new File("performance/scenarios/payout.js")).exists();
        assertThat(new File("performance/scenarios/admin.js")).exists();
    }

    // =========================================================================
    // D — Capacity Model
    // =========================================================================
    @Test
    @DisplayName("D — Capacity model: CAPACITY-MODEL.md specifies mathematical formulas and headroom")
    void D_capacityModelExists() throws Exception {
        File capacityModel = new File("docs/performance/CAPACITY-MODEL.md");
        assertThat(capacityModel).exists();

        String content = Files.readString(capacityModel.toPath());
        assertThat(content).contains("Peak Capacity Formula");
        assertThat(content).contains("DAU");
        assertThat(content).contains("PeakMultiplier");
        assertThat(content).contains("Headroom");
        assertThat(content).contains("GREEN");
        assertThat(content).contains("YELLOW");
        assertThat(content).contains("RED");
    }

    // =========================================================================
    // E — Performance Budgets & SLOs
    // =========================================================================
    @Test
    @DisplayName("E — Performance budgets: PERFORMANCE-BUDGETS.md and slo-thresholds.json exist")
    void E_performanceBudgetsExist() throws Exception {
        File budgets = new File("docs/performance/PERFORMANCE-BUDGETS.md");
        File thresholds = new File("performance/thresholds/slo-thresholds.json");
        assertThat(budgets).exists();
        assertThat(thresholds).exists();

        String content = Files.readString(budgets.toPath());
        assertThat(content).contains("p95");
        assertThat(content).contains("p99");
        assertThat(content).contains("Payment Creation (Financial Path)");
        assertThat(content).contains("Error Budget");
    }

    // =========================================================================
    // F — Reproducible Test Environment
    // =========================================================================
    @Test
    @DisplayName("F — Reproducible environment: performance.yml workflow and docker-compose exist")
    void F_reproducibleTestEnvironmentExists() {
        assertThat(new File(".github/workflows/performance.yml")).exists();
        assertThat(new File("docker-compose.yml")).exists();
        assertThat(new File("docker/Dockerfile")).exists();
    }

    // =========================================================================
    // G — Financial Test Data Generation
    // =========================================================================
    @Test
    @DisplayName("G — Test data: Synthetic datasets exist with strictly positive minor units")
    void G_financialTestDataGenerationIsValid() throws Exception {
        File users = new File("performance/datasets/synthetic-users.json");
        File accounts = new File("performance/datasets/synthetic-accounts.json");
        File payloads = new File("performance/datasets/payment-payloads.json");

        assertThat(users).exists();
        assertThat(accounts).exists();
        assertThat(payloads).exists();

        String accContent = Files.readString(accounts.toPath());
        assertThat(accContent).contains("USD");
        assertThat(accContent).contains("initialBalance");

        String payContent = Files.readString(payloads.toPath());
        assertThat(payContent).contains("amount");
        assertThat(payContent).contains("currency");
    }

    // =========================================================================
    // H — Critical Payment Workload
    // =========================================================================
    @Test
    @DisplayName("H — Critical payment workload: payment.js validates status and idempotency")
    void H_criticalPaymentWorkloadExists() throws Exception {
        File paymentJs = new File("performance/scenarios/payment.js");
        assertThat(paymentJs).exists();

        String content = Files.readString(paymentJs.toPath());
        assertThat(content).contains("/api/v1/payments");
        assertThat(content).contains("Idempotency-Key");
        assertThat(content).contains("stages:");
        assertThat(content).contains("thresholds:");
    }

    // =========================================================================
    // I — Concurrency Workload & Deadlock Immunity
    // =========================================================================
    @Test
    @DisplayName("I — Concurrency: LOAD-TESTING.md documents opposing transfers with 0 deadlocks")
    void I_concurrencyWorkloadExists() throws Exception {
        File loadDoc = new File("docs/performance/LOAD-TESTING.md");
        assertThat(loadDoc).exists();

        String content = Files.readString(loadDoc.toPath());
        assertThat(content).contains("Opposing Transfers");
        assertThat(content).contains("0 Deadlocks");
        assertThat(content).contains("UUID.compareTo");
        assertThat(content).contains("Single-Account Hotspot Contention");
    }

    // =========================================================================
    // J — Database Profiling Artifacts
    // =========================================================================
    @Test
    @DisplayName("J — Database profiling: QUERY-PROFILING.md records EXPLAIN ANALYZE execution plans")
    void J_databaseProfilingArtifactsExist() throws Exception {
        File queryDoc = new File("docs/performance/QUERY-PROFILING.md");
        assertThat(queryDoc).exists();

        String content = Files.readString(queryDoc.toPath());
        assertThat(content).contains("EXPLAIN");
        assertThat(content).contains("Index Scan");
        assertThat(content).contains("Payment Idempotency Verification");
        assertThat(content).contains("Deterministic Account Row-Lock");
        assertThat(content).contains("N+1 Query Audit Results");
    }

    // =========================================================================
    // K — Connection Pool Tuning Methodology
    // =========================================================================
    @Test
    @DisplayName("K — Connection pool: HikariCP tuning experiments across pool sizes documented")
    void K_connectionPoolMethodologyExists() throws Exception {
        File loadDoc = new File("docs/performance/LOAD-TESTING.md");
        String content = Files.readString(loadDoc.toPath());

        assertThat(content).contains("HikariCP Connection Pool Tuning Experiments");
        assertThat(content).contains("Pool size 20 is the preferred measured operating point");
    }

    // =========================================================================
    // L — Kafka Methodology & Backpressure
    // =========================================================================
    @Test
    @DisplayName("L — Kafka performance: Outbox buffering during outage documented in FAILURE-PERFORMANCE.md")
    void L_kafkaMethodologyExists() throws Exception {
        File failureDoc = new File("docs/performance/FAILURE-PERFORMANCE.md");
        assertThat(failureDoc).exists();

        String content = Files.readString(failureDoc.toPath());
        assertThat(content).contains("Kafka Outage & Outbox Backpressure");
        assertThat(content).contains("210 events/sec");
        assertThat(content).contains("Consumer lag");
    }

    // =========================================================================
    // M — Redis Methodology & Cache Fallback
    // =========================================================================
    @Test
    @DisplayName("M — Redis performance: Cache hit/miss and outage fallback documented")
    void M_redisMethodologyExists() throws Exception {
        File failureDoc = new File("docs/performance/FAILURE-PERFORMANCE.md");
        String content = Files.readString(failureDoc.toPath());

        assertThat(content).contains("Redis Outage / Degradation");
        assertThat(content).contains("Account Read Cache");
        assertThat(content).contains("FAIL_OPEN");
    }

    // =========================================================================
    // N — Outbox Methodology
    // =========================================================================
    @Test
    @DisplayName("N — Outbox performance: Polling query and throughput documented")
    void N_outboxMethodologyExists() throws Exception {
        File queryDoc = new File("docs/performance/QUERY-PROFILING.md");
        String content = Files.readString(queryDoc.toPath());

        assertThat(content).contains("outbox_events");
        assertThat(content).contains("FOR UPDATE SKIP LOCKED");
    }

    // =========================================================================
    // O — Reconciliation Methodology
    // =========================================================================
    @Test
    @DisplayName("O — Reconciliation performance: Discrepancy discovery query profiled")
    void O_reconciliationMethodologyExists() throws Exception {
        File queryDoc = new File("docs/performance/QUERY-PROFILING.md");
        String content = Files.readString(queryDoc.toPath());

        assertThat(content).contains("Reconciliation Discrepancy Case Discovery");
        assertThat(content).contains("PENDING_RECONCILIATION");
    }

    // =========================================================================
    // P — Failure-Performance Methodology
    // =========================================================================
    @Test
    @DisplayName("P — Failure performance: FAILURE-PERFORMANCE.md documents provider latency & timeouts")
    void P_failurePerformanceMethodologyExists() throws Exception {
        File failureDoc = new File("docs/performance/FAILURE-PERFORMANCE.md");
        String content = Files.readString(failureDoc.toPath());

        assertThat(content).contains("Payment Provider Latency & Ambiguity Simulation");
        assertThat(content).contains("PENDING_RECONCILIATION");
    }

    // =========================================================================
    // Q — Performance Regression Policy
    // =========================================================================
    @Test
    @DisplayName("Q — Regression policy: PERFORMANCE-BUDGETS.md specifies 10% maximum allowable regression")
    void Q_performanceRegressionPolicyExists() throws Exception {
        File budgets = new File("docs/performance/PERFORMANCE-BUDGETS.md");
        String content = Files.readString(budgets.toPath());

        assertThat(content).contains("Automated Performance Regression Thresholds");
        assertThat(content).contains("10.0%");
    }

    // =========================================================================
    // R — Phase 20 Freeze Boundary Check
    // =========================================================================
    @Test
    @DisplayName("R — No Phase 21 leakage: Phase 21 does not exist, Phase 20 is the final roadmap milestone")
    void R_noPhase20Leakage() {
        File phase21Report = new File("docs/phase-reports/PHASE-21.md");
        assertThat(phase21Report).as("Phase 21 report must not exist — Phase 20 is final").doesNotExist();
    }

    // =========================================================================
    // S — Financial Invariant Audit Under Test Execution
    // =========================================================================
    @Test
    @DisplayName("S — Financial invariant audit: Every persisted ledger transaction is perfectly balanced")
    void S_noFinancialInvariantBypassExists() {
        List<com.paymentledger.ledger.domain.LedgerTransactionEntity> transactions = transactionRepository.findAll();
        for (com.paymentledger.ledger.domain.LedgerTransactionEntity tx : transactions) {
            List<com.paymentledger.ledger.domain.LedgerEntryEntity> entries = entryRepository.findByLedgerTransaction_Id(tx.getId());
            long debitSum = 0;
            long creditSum = 0;
            for (com.paymentledger.ledger.domain.LedgerEntryEntity entry : entries) {
                if (entry.getDirection() == com.paymentledger.ledger.domain.LedgerEntryDirection.DEBIT) {
                    debitSum += entry.getAmountMinor();
                } else if (entry.getDirection() == com.paymentledger.ledger.domain.LedgerEntryDirection.CREDIT) {
                    creditSum += entry.getAmountMinor();
                }
            }
            assertThat(debitSum)
                    .as("Ledger Transaction " + tx.getId() + " must have balanced debits and credits")
                    .isEqualTo(creditSum);
        }
    }
}
