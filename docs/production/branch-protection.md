# Branch Protection, Merge Governance & Quality Gate Policy

**Status**: ACTIVE / ENFORCED  
**Applies To**: `main`, `release/**`  
**Governing Phase**: Phase 18 — CI Quality Gates, Security Scanning & Branch Protection  

---

## 1. Overview & Objective

In a mission-critical distributed payment and immutable ledger platform, code integrity, financial invariants, and security boundaries must be strictly defended at the Git branch level.

This policy specifies the branch protection rules, required CI/CD status checks, peer review mandates, and cryptographic signing requirements enforced on the `main` and `release/**` branches.

---

## 2. Required Status Checks (Blocking Gates)

Direct pushes to `main` and `release/**` are strictly prohibited. All modifications must arrive via Pull Requests that successfully satisfy **100% of required status checks**:

| Status Check / Job Name | Workflow | Enforced Guarantee | Severity on Failure |
|---|---|---|---|
| `Pre-Flight & Migration Gates` | `ci.yml` | Validates no `.env`, Flyway monotonic sequence, non-empty SQL, and production YAML constraints. | **BLOCKS MERGE** |
| `Build, Test & Coverage Gate` | `ci.yml` | Enforces Java 21/Maven 3.9, 100% test pass (unit & integration), and JaCoCo coverage analysis. | **BLOCKS MERGE** |
| `Reproducible Build & Integrity` | `ci.yml` | Packages deterministic JAR, verifies `project.build.outputTimestamp`, and calculates SHA-256 checksums. | **BLOCKS MERGE** |
| `Quality Gate Evaluation` | `ci.yml` | Final composite verdict asserting that all build and validation gates succeeded. | **BLOCKS MERGE** |
| `Secret & Credential Scanning` | `security.yml` | Scans all commits and files for leaked API keys, tokens, private keys, or plain passwords. | **BLOCKS MERGE** |
| `Dependency Vulnerability Scanning` | `security.yml` | Scans dependencies with Trivy for known CVEs (`CRITICAL` and `HIGH`). | **BLOCKS MERGE** |
| `Container Build & Vulnerability Scan` | `security.yml` | Builds Docker image from `docker/Dockerfile` and scans image layers for base vulnerabilities. | **BLOCKS MERGE** |
| `Security Gate Evaluation` | `security.yml` | Composite security verdict ensuring no blocking security defects. | **BLOCKS MERGE** |

---

## 3. Pull Request Review Requirements

1. **Minimum Approvals**: Every Pull Request requires at least **two (2) independent peer review approvals** from platform engineers before becoming eligible for merge.
2. **CODEOWNERS Enforcement**:
   - Changes affecting `src/main/java/com/paymentledger/ledger/**` require approval from `@financial-core-team`.
   - Changes affecting `src/main/java/com/paymentledger/auth/**` or security configs require approval from `@platform-security-team`.
   - Changes affecting `src/main/resources/db/migration/**` require approval from `@database-architecture-team`.
3. **Dismiss Stale Approvals**: When new commits are pushed to a pull request branch, all previous approvals are automatically invalidated and new reviews are required.
4. **Require Conversation Resolution**: All review comments and discussions must be formally resolved before merging.

---

## 4. History & Cryptographic Signing Constraints

1. **Linear History Required**:
   - Merge commits (`git merge`) are strictly forbidden.
   - All merges must use **Squash and Merge** (for feature branches) or **Rebase and Merge** (for release branches) to preserve a clean, bisectable linear history.
2. **Branches Must Be Up to Date**:
   - PR branches must be rebased onto the latest `main` commit before merge to prevent merge conflicts or hidden regressions.
3. **Signed Commits Mandatory**:
   - Every commit must be cryptographically signed using GPG or SSH (`git commit -S`).
   - Unverified commits will be rejected by GitHub/GitLab branch protection rules.
4. **No Force Pushes**:
   - `git push --force` and `git push --force-with-lease` are disabled for all users, including repository administrators.
5. **No Branch Deletion**:
   - Deletion of `main` or active `release/**` branches is permanently blocked.

---

## 5. Administrative Enforcement

- **Enforce on Administrators**: The "Do not allow bypassing the above settings" setting is enabled. Repository administrators and organization owners are subject to the same review, signature, and status check requirements without exception.

---

## 6. Emergency Hotfix & Break-Glass Protocol

In an active production incident (P0/SEV-1) where an immediate hotfix is required:
1. **Dual-Authorization**: Hotfix branch requires written dual-approval from both the Engineering Director and Incident Commander.
2. **Fast-Track Pipeline**: The hotfix PR must still pass automated unit tests, secret scanning, and migration checks.
3. **Post-Incident Audit**: Within 24 hours of incident resolution, a comprehensive audit log entry and post-mortem review must be documented in `docs/production/incident-response.md`.
