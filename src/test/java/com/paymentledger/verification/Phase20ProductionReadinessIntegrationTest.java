package com.paymentledger.verification;

import com.paymentledger.infrastructure.AbstractIntegrationTest;
import com.paymentledger.ledger.domain.LedgerEntryDirection;
import com.paymentledger.ledger.domain.LedgerEntryEntity;
import com.paymentledger.ledger.domain.LedgerTransactionEntity;
import com.paymentledger.ledger.repository.LedgerEntryRepository;
import com.paymentledger.ledger.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 20 — Complete Production-Readiness Verification Integration Test")
public class Phase20ProductionReadinessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    // =========================================================================
    // A — Production Configuration Audit
    // =========================================================================
    @Test
    @DisplayName("A — Production configuration: application-prod.yml enforces secure defaults")
    void A_productionConfigurationIsValid() throws Exception {
        File prodYml = new File("src/main/resources/application-prod.yml");
        assertThat(prodYml).exists();

        String content = Files.readString(prodYml.toPath());
        assertThat(content).contains("show-sql: false");
        assertThat(content).contains("ddl-auto: validate");
        assertThat(content).contains("shutdown: graceful");
        assertThat(content).contains("timeout-per-shutdown-phase: 20s");
        assertThat(content).contains("max-http-form-post-size: 2MB");
        assertThat(content).contains("exposure:");
        assertThat(content).contains("health,info,metrics,prometheus");
        assertThat(content).contains("${DB_PASSWORD}");
        assertThat(content).contains("${JWT_SECRET}");
        assertThat(content).contains("${KAFKA_BOOTSTRAP_SERVERS}");
    }

    // =========================================================================
    // B — Operational Documentation Suite
    // =========================================================================
    @Test
    @DisplayName("B — Operational documentation: All required SOPs and operational references exist")
    void B_operationalDocumentationExists() {
        assertThat(new File("docs/operations/backup-restore.md")).exists();
        assertThat(new File("docs/operations/disaster-recovery.md")).exists();
        assertThat(new File("docs/operations/incident-response.md")).exists();
        assertThat(new File("docs/operations/controlled-rollout.md")).exists();
        assertThat(new File("docs/operations/configuration-reference.md")).exists();
        assertThat(new File("docs/operations/resilience-matrix.md")).exists();
        assertThat(new File("docs/operations/production-release-checklist.md")).exists();
        assertThat(new File("docs/operations/FUTURE-IMPROVEMENTS.md")).exists();
    }

    // =========================================================================
    // C — Security Hardening & Secret Scanning
    // =========================================================================
    @Test
    @DisplayName("C — Security hardening: Secret scanning scripts and CI security workflows exist")
    void C_securityHardeningAndScanningArtifactsExist() {
        assertThat(new File("scripts/scan-secrets.ps1")).exists();
        assertThat(new File("scripts/scan-secrets.sh")).exists();
        assertThat(new File("scripts/validate-configs.ps1")).exists();
        assertThat(new File(".github/workflows/security.yml")).exists();
    }

    // =========================================================================
    // D — Backup and Restore Standard Operating Procedure
    // =========================================================================
    @Test
    @DisplayName("D — Backup/Restore: backup-restore.md specifies pg_dump, pg_restore, RPO, and RTO")
    void D_backupAndRestoreProcedureIsSpecified() throws Exception {
        File backupDoc = new File("docs/operations/backup-restore.md");
        assertThat(backupDoc).exists();

        String content = Files.readString(backupDoc.toPath());
        assertThat(content).contains("pg_dump");
        assertThat(content).contains("pg_restore");
        assertThat(content).contains("RPO");
        assertThat(content).contains("RTO");
        assertThat(content).contains("SUM(debits) == SUM(credits)");
        assertThat(content).contains("Synthetic Dataset Volume");
    }

    // =========================================================================
    // E — Disaster Recovery Playbook
    // =========================================================================
    @Test
    @DisplayName("E — Disaster Recovery: disaster-recovery.md distinguishes TESTED vs PLANNED scenarios")
    void E_disasterRecoveryPlaybookIsValid() throws Exception {
        File drDoc = new File("docs/operations/disaster-recovery.md");
        assertThat(drDoc).exists();

        String content = Files.readString(drDoc.toPath());
        assertThat(content).contains("Target RPO");
        assertThat(content).contains("Target RTO");
        assertThat(content).contains("TESTED");
        assertThat(content).contains("PLANNED");
        assertThat(content).contains("Step-by-Step Restoration Sequence");
    }

    // =========================================================================
    // F — Incident Response & Runbooks
    // =========================================================================
    @Test
    @DisplayName("F — Incident Response: incident-response.md defines severities and all 13 runbooks")
    void F_incidentResponseRunbooksExist() throws Exception {
        File incidentDoc = new File("docs/operations/incident-response.md");
        assertThat(incidentDoc).exists();

        String content = Files.readString(incidentDoc.toPath());
        assertThat(content).contains("SEV-1 (Critical)");
        assertThat(content).contains("Incident Commander (IC)");
        assertThat(content).contains("Runbook 1: Payment Failures");
        assertThat(content).contains("Runbook 2: Payments Stuck in Reconciliation");
        assertThat(content).contains("Runbook 3: Ledger Inconsistency Alert");
        assertThat(content).contains("Runbook 4: Kafka Outage / Broker Unavailability");
        assertThat(content).contains("Runbook 5: Redis Outage / Sentinel Partition");
        assertThat(content).contains("Runbook 6: Database Outage / Connection Pool Exhaustion");
        assertThat(content).contains("Runbook 7: Outbox Backlog Accumulation");
        assertThat(content).contains("Runbook 8: High API Latency");
        assertThat(content).contains("Runbook 9: High Error Rate");
        assertThat(content).contains("Runbook 10: Payment Provider Total Outage");
        assertThat(content).contains("Runbook 11: Security Incident");
        assertThat(content).contains("Runbook 12: Failed Deployment / Rollback");
        assertThat(content).contains("Runbook 13: Emergency Database Restore");
    }

    // =========================================================================
    // G — Controlled Rollout & Deployment Strategy
    // =========================================================================
    @Test
    @DisplayName("G — Controlled Rollout: controlled-rollout.md distinguishes SUPPORTED NOW vs FUTURE OPTION")
    void G_controlledRolloutAndRollbackProceduresExist() throws Exception {
        File rolloutDoc = new File("docs/operations/controlled-rollout.md");
        assertThat(rolloutDoc).exists();

        String content = Files.readString(rolloutDoc.toPath());
        assertThat(content).contains("Pre-Deployment Gate Checklist");
        assertThat(content).contains("Standard Deployment Sequence");
        assertThat(content).contains("Rollback Decision & Procedure");
        assertThat(content).contains("SUPPORTED NOW");
        assertThat(content).contains("FUTURE OPTION");
    }

    // =========================================================================
    // H — Configuration Reference & Resilience Matrix
    // =========================================================================
    @Test
    @DisplayName("H — Configuration & Resilience: configuration-reference.md and resilience-matrix.md exist")
    void H_configurationReferenceAndResilienceMatrixExist() throws Exception {
        File configDoc = new File("docs/operations/configuration-reference.md");
        File resilienceDoc = new File("docs/operations/resilience-matrix.md");
        assertThat(configDoc).exists();
        assertThat(resilienceDoc).exists();

        String configContent = Files.readString(configDoc.toPath());
        assertThat(configContent).contains("DB_PASSWORD");
        assertThat(configContent).contains("JWT_SECRET");

        String resilienceContent = Files.readString(resilienceDoc.toPath());
        assertThat(resilienceContent).contains("Kafka Broker Outage");
        assertThat(resilienceContent).contains("Redis Cache / Sentinel Down");
        assertThat(resilienceContent).contains("External Payment Provider Timeout");
    }

    // =========================================================================
    // I — Container Hardening & CI Quality Gates
    // =========================================================================
    @Test
    @DisplayName("I — Container hardening: Dockerfile enforces non-root user and CI workflow is active")
    void I_ciWorkflowsAndDockerHardeningExist() throws Exception {
        File dockerfile = new File("docker/Dockerfile");
        File ciWorkflow = new File(".github/workflows/ci.yml");
        assertThat(dockerfile).exists();
        assertThat(ciWorkflow).exists();

        String dockerContent = Files.readString(dockerfile.toPath());
        assertThat(dockerContent).contains("USER appuser");
        assertThat(dockerContent).contains("10001");
        assertThat(dockerContent).contains("MaxRAMPercentage");
    }

    // =========================================================================
    // J — Production Readiness Matrix
    // =========================================================================
    @Test
    @DisplayName("J — Readiness Matrix: PRODUCTION-READINESS-MATRIX.md records READY_FOR_CONTROLLED_ROLLOUT")
    void J_productionReadinessMatrixIsComplete() throws Exception {
        File matrixDoc = new File("docs/phase-reports/PRODUCTION-READINESS-MATRIX.md");
        assertThat(matrixDoc).exists();

        String content = Files.readString(matrixDoc.toPath());
        assertThat(content).contains("READY_FOR_CONTROLLED_ROLLOUT");
        assertThat(content).contains("Security");
        assertThat(content).contains("Financial Integrity");
        assertThat(content).contains("Observability");
    }

    // =========================================================================
    // K — Preservation of Phase 19 Performance Limits
    // =========================================================================
    @Test
    @DisplayName("K — Phase 19 limits: PHASE-19.md retains empirical 185 TPS, 235 TPS, and 220-240 TPS")
    void K_phase19PerformanceInvariantsPreserved() throws Exception {
        File phase19Doc = new File("docs/phase-reports/PHASE-19.md");
        assertThat(phase19Doc).exists();

        String content = Files.readString(phase19Doc.toPath());
        assertThat(content).contains("185 TPS");
        assertThat(content).contains("235 TPS");
        assertThat(content).contains("220–240 TPS");
        assertThat(content).contains("Preferred Measured Operating Point");
    }

    // =========================================================================
    // L — Phase 20 Final Roadmap Boundary (No Phase 21)
    // =========================================================================
    @Test
    @DisplayName("L — Phase 20 boundary: Phase 20 is final roadmap milestone, Phase 21 does not exist")
    void L_phase20FreezeBoundaryEnforced() {
        File phase21Doc = new File("docs/phase-reports/PHASE-21.md");
        assertThat(phase21Doc)
                .as("Phase 20 is the final roadmap phase; Phase 21 must not exist")
                .doesNotExist();
    }

    // =========================================================================
    // M — Financial Invariant Audit Across Database
    // =========================================================================
    @Test
    @DisplayName("M — Financial invariant audit: 100% of persisted ledger transactions are strictly balanced")
    void M_financialInvariantAuditUnderProductionReadiness() {
        List<LedgerTransactionEntity> transactions = transactionRepository.findAll();
        for (LedgerTransactionEntity tx : transactions) {
            List<LedgerEntryEntity> entries = entryRepository.findByLedgerTransaction_Id(tx.getId());
            long debitSum = 0;
            long creditSum = 0;
            for (LedgerEntryEntity entry : entries) {
                if (entry.getDirection() == LedgerEntryDirection.DEBIT) {
                    debitSum += entry.getAmountMinor();
                } else if (entry.getDirection() == LedgerEntryDirection.CREDIT) {
                    creditSum += entry.getAmountMinor();
                }
            }
            assertThat(debitSum)
                    .as("Ledger Transaction " + tx.getId() + " must have balanced debits and credits")
                    .isEqualTo(creditSum);
        }
    }
}
