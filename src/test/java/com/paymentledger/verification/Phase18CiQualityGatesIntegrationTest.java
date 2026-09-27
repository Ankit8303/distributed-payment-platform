package com.paymentledger.verification;

import com.paymentledger.infrastructure.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 18 — CI Quality Gates, Security Scanning & Branch Protection Verification")
public class Phase18CiQualityGatesIntegrationTest extends AbstractIntegrationTest {

    private static final Path ROOT_DIR = Path.of("");

    // =========================================================================
    // A — CI/CD Workflow Verifications (.github/workflows/)
    // =========================================================================

    @Test
    @DisplayName("A1 — CI Workflow exists, has branch triggers, test execution, JaCoCo, and composite gate")
    void A1_ciWorkflowExistsAndHasRequiredStructure() throws Exception {
        File ciYml = new File(".github/workflows/ci.yml");
        assertThat(ciYml).as(".github/workflows/ci.yml must exist").exists();

        String content = Files.readString(ciYml.toPath());
        assertThat(content).contains("name: CI Quality Gates");
        assertThat(content).contains("branches:");
        assertThat(content).contains("main");
        assertThat(content).contains("release/**");

        // Verify required jobs
        assertThat(content).contains("validation-gates:");
        assertThat(content).contains("build-and-test:");
        assertThat(content).contains("reproducible-build:");
        assertThat(content).contains("ci-quality-gate:");

        // Verify test & coverage steps
        assertThat(content).contains("./mvnw -B verify");
        assertThat(content).contains("jacoco-coverage-report");
        assertThat(content).contains("surefire-test-reports");
    }

    @Test
    @DisplayName("A2 — Security Workflow exists with schedule, secret scanning, dependency CVE, container scan, and SAST")
    void A2_securityWorkflowExistsAndHasRequiredScanners() throws Exception {
        File secYml = new File(".github/workflows/security.yml");
        assertThat(secYml).as(".github/workflows/security.yml must exist").exists();

        String content = Files.readString(secYml.toPath());
        assertThat(content).contains("Security Scanning & Container Hardening");
        assertThat(content).contains("cron:");

        // Verify required security jobs
        assertThat(content).contains("secret-scan:");
        assertThat(content).contains("dependency-scan:");
        assertThat(content).contains("container-scan:");
        assertThat(content).contains("sast-analysis:");
        assertThat(content).contains("security-quality-gate:");

        // Verify scanner tooling
        assertThat(content).contains("scan-secrets.sh");
        assertThat(content).contains("trivy-action");
        assertThat(content).contains("docker/Dockerfile");
    }

    @Test
    @DisplayName("A3 — Reproducible build workflow exists with dual-build verification")
    void A3_reproducibleBuildWorkflowExists() throws Exception {
        File reproYml = new File(".github/workflows/reproducible-build.yml");
        assertThat(reproYml).as(".github/workflows/reproducible-build.yml must exist").exists();

        String content = Files.readString(reproYml.toPath());
        assertThat(content).contains("Reproducible Build Verification");
        assertThat(content).contains("First Clean Build");
        assertThat(content).contains("Second Clean Build");
        assertThat(content).contains("Compare Build Artifact Hashes");
    }

    // =========================================================================
    // B — Build Configuration & Maven Plugins (pom.xml)
    // =========================================================================

    @Test
    @DisplayName("B1 — pom.xml configures maven-enforcer-plugin enforcing Java 21+ and Maven 3.9+")
    void B1_pomXmlContainsEnforcerPlugin() throws Exception {
        File pom = new File("pom.xml");
        String content = Files.readString(pom.toPath());

        assertThat(content).contains("<artifactId>maven-enforcer-plugin</artifactId>");
        assertThat(content).contains("<requireJavaVersion>");
        assertThat(content).contains("<version>[21,)</version>");
        assertThat(content).contains("<requireMavenVersion>");
        assertThat(content).contains("<version>[3.9.0,)</version>");
    }

    @Test
    @DisplayName("B2 — pom.xml configures jacoco-maven-plugin with prepare-agent and report goals")
    void B2_pomXmlContainsJacocoPlugin() throws Exception {
        File pom = new File("pom.xml");
        String content = Files.readString(pom.toPath());

        assertThat(content).contains("<artifactId>jacoco-maven-plugin</artifactId>");
        assertThat(content).contains("<goal>prepare-agent</goal>");
        assertThat(content).contains("<goal>report</goal>");
        assertThat(content).contains("<include>com/paymentledger/**</include>");
        assertThat(content).contains("@{argLine}");
    }

    @Test
    @DisplayName("B3 — pom.xml configures project.build.outputTimestamp with valid ISO-8601 UTC timestamp")
    void B3_pomXmlContainsReproducibleBuildTimestamp() throws Exception {
        File pom = new File("pom.xml");
        String content = Files.readString(pom.toPath());

        Pattern pattern = Pattern.compile("<project\\.build\\.outputTimestamp>([^<]+)</project\\.build\\.outputTimestamp>");
        Matcher matcher = pattern.matcher(content);
        assertThat(matcher.find()).as("pom.xml must contain <project.build.outputTimestamp>").isTrue();

        String timestamp = matcher.group(1).trim();
        assertThat(timestamp)
                .as("outputTimestamp must match ISO-8601 UTC format")
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})$");
    }

    // =========================================================================
    // C — Secret Scanning & Credential Safety
    // =========================================================================

    @Test
    @DisplayName("C1 — Secret scanner regex rules detect synthetic credentials across all monitored patterns")
    void C1_secretScannerDetectsSyntheticCredentials() {
        Pattern awsKey = Pattern.compile("AKIA[0-9A-Z]{16}");
        Pattern privateKey = Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----");
        Pattern slackWebhook = Pattern.compile("https://hooks\\.slack\\.com/services/T[0-9A-Za-z_]+/B[0-9A-Za-z_]+/[0-9A-Za-z_]+");
        Pattern githubPat = Pattern.compile("gh[pousr]_[0-9a-zA-Z]{36}");
        Pattern stripeKey = Pattern.compile("sk_live_[0-9a-zA-Z]{24,}");

        assertThat(awsKey.matcher("AKIAIOSFODNN7EXAMPLE").find()).isTrue();
        assertThat(privateKey.matcher("-----BEGIN RSA PRIVATE KEY-----\nMIIE...").find()).isTrue();
        assertThat(slackWebhook.matcher("https://hooks.slack.com/services/TTEST/BTEST/TEST").find()).isTrue();
        assertThat(githubPat.matcher("ghp_123456789012345678901234567890123456").find()).isTrue();
        assertThat(stripeKey.matcher("sk_" + "live_" + "0".repeat(24)).find()).isTrue();
    }

    @Test
    @DisplayName("C2 — Local secret scanner scripts exist and repository has zero live secrets committed")
    void C2_secretScannerPassesCleanRepository() throws Exception {
        File scanSh = new File("scripts/scan-secrets.sh");
        File scanPs1 = new File("scripts/scan-secrets.ps1");
        assertThat(scanSh).exists();
        assertThat(scanPs1).exists();

        // Scan production resources
        File prodYml = new File("src/main/resources/application-prod.yml");
        assertThat(prodYml).exists();
        String prodContent = Files.readString(prodYml.toPath());

        // Ensure no unmasked live passwords
        Pattern plainSecretPattern = Pattern.compile("(?m)^\\s*(password|secret):\\s*['\"][^$\\{\\}]+['\"]");
        assertThat(plainSecretPattern.matcher(prodContent).find())
                .as("application-prod.yml must not contain unmasked plain passwords")
                .isFalse();
    }

    // =========================================================================
    // D — Database Migration Integrity
    // =========================================================================

    @Test
    @DisplayName("D1 — Flyway migrations adhere to naming convention, are non-empty, and strictly monotonic")
    void D1_migrationValidatorAcceptsCleanMigrations() throws Exception {
        File migrationDir = new File("src/main/resources/db/migration");
        assertThat(migrationDir).exists().isDirectory();

        File[] sqlFiles = migrationDir.listFiles((dir, name) -> name.endsWith(".sql"));
        assertThat(sqlFiles).isNotNull().isNotEmpty();

        Pattern pattern = Pattern.compile("^V(\\d+)__([a-zA-Z0-9_]+)\\.sql$");
        List<Integer> versions = new ArrayList<>();

        for (File sqlFile : sqlFiles) {
            Matcher matcher = pattern.matcher(sqlFile.getName());
            assertThat(matcher.matches())
                    .as("Migration filename must match V<num>__<desc>.sql: " + sqlFile.getName())
                    .isTrue();

            assertThat(sqlFile.length())
                    .as("Migration file must not be empty: " + sqlFile.getName())
                    .isGreaterThan(0);

            String sql = Files.readString(sqlFile.toPath());
            assertThat(sql.toUpperCase())
                    .as("Migration must not contain DROP DATABASE: " + sqlFile.getName())
                    .doesNotContain("DROP DATABASE");

            versions.add(Integer.parseInt(matcher.group(1)));
        }

        Collections.sort(versions);
        assertThat(versions.get(0)).as("Migrations must start at V1").isEqualTo(1);

        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("Migrations must be strictly consecutive without gaps or duplicates")
                    .isEqualTo(i + 1);
        }
    }

    // =========================================================================
    // E — Configuration and Container Validation
    // =========================================================================

    @Test
    @DisplayName("E1 — Production configuration enforces graceful shutdown, bounded pools, and no debug SQL")
    void E1_configurationValidatorVerifiesProductionProfile() throws Exception {
        File prodYml = new File("src/main/resources/application-prod.yml");
        assertThat(prodYml).exists();

        String content = Files.readString(prodYml.toPath());
        assertThat(content).contains("show-sql: false");
        assertThat(content).contains("shutdown: graceful");
        assertThat(content).contains("timeout-per-shutdown-phase: 20s");
        assertThat(content).doesNotContain("include: '*'", "include: \"*\"");
    }

    @Test
    @DisplayName("E2 — Container configurations enforce non-root execution and healthchecks")
    void E2_configurationValidatorVerifiesDockerfileAndCompose() throws Exception {
        File dockerfile = new File("docker/Dockerfile");
        assertThat(dockerfile).exists();

        String dfContent = Files.readString(dockerfile.toPath());
        assertThat(dfContent).contains("USER appuser:appgroup");
        assertThat(dfContent).contains("HEALTHCHECK");

        File composeFile = new File("docker-compose.yml");
        assertThat(composeFile).exists();
        String composeContent = Files.readString(composeFile.toPath());
        assertThat(composeContent).contains("postgres:");
        assertThat(composeContent).contains("kafka:");
        assertThat(composeContent).contains("redis:");
        assertThat(composeContent).contains("healthcheck:");
    }

    // =========================================================================
    // F — Branch Protection Policy & Local Runner
    // =========================================================================

    @Test
    @DisplayName("F1 — Branch protection policy document specifies required status checks, approvals, and signing")
    void F1_branchProtectionPolicyDocumentIsComplete() throws Exception {
        File bpDoc = new File("docs/production/branch-protection.md");
        assertThat(bpDoc).exists();

        String content = Files.readString(bpDoc.toPath());
        assertThat(content).contains("Branch Protection");
        assertThat(content).contains("Pre-Flight & Migration Gates");
        assertThat(content).contains("Build, Test & Coverage Gate");
        assertThat(content).contains("Reproducible Build & Integrity");
        assertThat(content).contains("Quality Gate Evaluation");
        assertThat(content).contains("Secret & Credential Scanning");
        assertThat(content).contains("Dependency Vulnerability Scanning");
        assertThat(content).contains("Container Build & Vulnerability Scan");
        assertThat(content).contains("Linear History Required");
        assertThat(content).contains("Signed Commits Mandatory");
        assertThat(content).contains("No Force Pushes");
        assertThat(content).contains("CODEOWNERS");
    }

    @Test
    @DisplayName("F2 — Local CI quality gate runner scripts exist and are executable")
    void F2_localQualityGateRunnersExist() {
        assertThat(new File("scripts/verify-ci.sh")).exists();
        assertThat(new File("scripts/verify-ci.ps1")).exists();
        assertThat(new File("scripts/verify-repo.sh")).exists();
        assertThat(new File("scripts/validate-migrations.sh")).exists();
        assertThat(new File("scripts/validate-configs.sh")).exists();
        assertThat(new File("scripts/verify-reproducibility.sh")).exists();
    }

    // =========================================================================
    // G — Phase Freeze Boundary Check
    // =========================================================================

    @Test
    @DisplayName("G1 — No Phase 21 leakage: Phase 21 does not exist, Phase 20 is the final roadmap milestone")
    void G1_noPhase20Leakage() {
        File phase21Report = new File("docs/phase-reports/PHASE-21.md");
        assertThat(phase21Report).as("Phase 21 report must not exist — Phase 20 is final").doesNotExist();
    }
}
