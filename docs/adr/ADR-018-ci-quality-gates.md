# ADR-018: CI Quality Gates, Security Scanning, Reproducible Builds & Branch Protection

**Status**: APPROVED  
**Date**: 2026-09-25  
**Deciders**: Lead DevSecOps / Platform Engineer, Security Architecture Team  
**Consulted**: Financial Platform Architects, Production Operations, Release Engineering  

---

## 1. Context and Problem Statement

Phases 0 through 17 established rigorous guarantees for the Distributed Payment & Ledger Platform:
- Strict double-entry ledger balancing (`SUM(debit) == SUM(credit)`) in integer minor units.
- PostgreSQL as the sole authoritative financial source of truth.
- Durable idempotency, safe outbox publishing, and auxiliary caching/rate-limiting isolation.
- Production-hardened containerization, secret isolation, and defensive timeouts.

Without automated, non-bypassable CI/CD quality gates, future code modifications, refactorings, or external contributions could silently introduce regressions, security vulnerabilities, secret leaks, schema inconsistencies, or non-deterministic build artifacts.

Phases 0 through 17 are **FROZEN**. Phase 18 must convert these guarantees into automated enforcement mechanisms without altering business functionality, changing database architectures, or introducing cloud-specific or cluster-level infrastructure (no Kubernetes, Terraform, or service mesh).

---

## 2. Decision Drivers

1. **Deterministic Quality Enforcement**: Any pull request that breaks a financial invariant, fails a test, drops code coverage, or violates schema rules must be automatically blocked from merging.
2. **Zero Credential Leaks**: Automated pre-commit and CI scanning to ensure no live secrets, private keys, or API tokens can ever enter the Git commit history.
3. **Supply Chain & Dependency Safety**: Continuous scanning of dependencies and container base images for known vulnerabilities (CVEs) using standard open tooling (Trivy, Gitleaks, Maven Enforcer).
4. **Build Reproducibility & Integrity**: Ensuring that any build from a given Git commit hash produces byte-for-byte deterministic JAR artifacts verifiable by cryptographic SHA-256 checksums.
5. **Strict Branch Governance**: Formal branch protection rules on `main` and `release/**` enforcing peer reviews, linear history, signed commits, and non-bypassable status checks.

---

## 3. Considered Options

- **Option A (Rejected)**: Rely on manual PR checklists, developer vigilance, and periodic manual security reviews.
  - *Reason for rejection*: Unacceptable for a financial ledger platform. Human error leads to regressions, leaked credentials, and untracked CVEs.
- **Option B (Rejected)**: Introduce heavyweight proprietary enterprise scanning suites requiring dedicated cloud infrastructure and external agents.
  - *Reason for rejection*: Violates Phase 18 constraints (no cloud infrastructure, preserve modular monolith conventions).
- **Option C (Accepted)**: Automated multi-stage GitHub Actions CI/CD pipeline paired with lightweight local validation scripts, standard Maven plugins (`jacoco-maven-plugin`, `maven-enforcer-plugin`), deterministic `project.build.outputTimestamp`, and comprehensive branch protection policies.

---

## 4. Architectural Decisions

### 4.1 CI Pipeline Architecture (`.github/workflows/ci.yml`)
The continuous integration pipeline is partitioned into distinct sequential and parallel stages:
1. **Pre-flight & Validation Gates**:
   - `scripts/verify-repo.sh` / `scripts/scan-secrets.sh`: Verifies clean repository without `.env` or leaked secrets.
   - `scripts/validate-migrations.sh`: Validates Flyway migration files for strict sequential versioning (`V1` to `VN`), naming conventions, and absence of destructive queries.
   - `scripts/validate-configs.sh`: Validates `application-prod.yml`, `docker/Dockerfile`, and `docker-compose.yml`.
2. **Build, Test & Coverage Gate**:
   - `maven-enforcer-plugin`: Enforces Java 21+ and Maven 3.9+.
   - `mvn clean test-compile`: Asserts strict clean compilation.
   - `mvn verify`: Executes all 400+ unit, slice, and integration tests across PostgreSQL, Kafka, and Redis testcontainers.
   - `jacoco-maven-plugin`: Collects execution data scoped strictly to `com/paymentledger/**` and publishes coverage reports.
3. **Reproducible Build & Integrity**:
   - Enforces `<project.build.outputTimestamp>` property in `pom.xml`.
   - Packages deterministic JAR artifacts and generates SHA-256 checksum files.
4. **Composite Quality Gate**:
   - Evaluates all prior stages; failure in any job blocks the entire PR pipeline.

### 4.2 Security Pipeline Architecture (`.github/workflows/security.yml`)
Runs on all PRs, pushes to protected branches, and weekly scheduled cron (`0 3 * * 1`):
1. **Secret & Credential Scanning**: Gitleaks and local script scanning for AWS keys, private keys, Stripe secrets, GitHub tokens, and Slack webhooks.
2. **Dependency CVE Scanning**: Trivy filesystem scan inspecting all Maven dependencies for critical/high vulnerabilities.
3. **Container Image Scanning**: Builds `docker/Dockerfile` using multi-stage build and scans generated Alpine/Temurin container layers with Trivy.
4. **Static Application Security Testing (SAST)**: Semgrep security audit covering OWASP Top 10 vulnerabilities.

### 4.3 Reproducible Build Specification
- Pinned `project.build.outputTimestamp` to ISO-8601 UTC timestamp (`2026-09-25T00:00:00Z`).
- Preserves identical file ordering and entry timestamps inside generated JAR files.
- Dual-build verification (`reproducible-build.yml`) confirms that consecutive clean builds produce identical SHA-256 hashes.

### 4.4 Branch Protection & Merge Governance (`docs/production/branch-protection.md`)
- Protected branches: `main`, `release/**`.
- Mandatory status checks: All CI and Security jobs must pass before merge.
- Required approvals: Minimum 2 senior platform engineers.
- CODEOWNERS: Changes to ledger core, auth, security, or migrations require specialized team sign-off.
- Invalidation on push: New commits automatically dismiss existing approvals.
- Linear history: Squash and merge or rebase and merge only; merge commits blocked.
- Cryptographic signatures: Mandatory GPG/SSH signed commits.
- Administrative enforcement: Rules apply unconditionally to repository administrators.

---

## 5. Consequences & Invariant Validation

### Positive Consequences
- **Zero Financial Regression**: Any violation of double-entry rules or state machine logic immediately fails CI.
- **Supply Chain Hardening**: Outdated or vulnerable dependencies are caught before reaching production.
- **Deterministic Deliverables**: Every release artifact can be independently verified and audited against source control.
- **Fast Developer Feedback**: Local scripts (`scripts/verify-ci.sh`, `scripts/verify-ci.ps1`) allow developers to run the entire gate suite locally before pushing.

### Trade-offs & Mitigations
- **Build Duration**: Running full integration tests with Testcontainers takes approximately 2-3 minutes.
  - *Mitigation*: Dependency caching (`cache: maven`) and parallel job execution in GitHub Actions minimize workflow runtimes.
- **Strict Signing**: Requiring signed commits requires developer setup.
  - *Mitigation*: Documented in branch protection guidelines with standard GPG/SSH workflows.
