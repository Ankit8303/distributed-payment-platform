# Security Audit — Distributed Payment & Ledger Platform

**Repository:** `Ankit8303/distributed-payment-platform`  
**Review date:** 2026-09-27  
**Review scope:** Repository source, CI/CD workflows, production configuration, container definition, security tests, and GitHub repository state.  
**Reference standard:** OWASP ASVS 5.0 and OWASP API Security principles. ASVS is used as a verification framework; this document is not a regulatory certification.  

## Auditor Status

**Repository security gate:** PASS on hardened PR #1 after remediation.  
**Production authorization:** NOT AUTOMATIC. A controlled staging/canary deployment and environment-specific operational verification remain required.

OWASP ASVS provides a basis for testing application security controls rather than merely documenting intended controls.

## Findings and Remediation

### SEC-001 — Hard-coded development credentials in Compose
**Severity:** HIGH  
**Status:** REMEDIATED

The original repository contained development PostgreSQL and Grafana passwords directly in `docker-compose.yml`. This caused the initial Gitleaks security workflow to fail.

**Remediation:** Credentials are now required through environment variables; `.env.example` contains placeholders rather than usable secrets.

**Verification:** Hardened PR security workflow passed secret scanning.

### SEC-002 — Authentication rate limiting failed open
**Severity:** HIGH  
**Status:** REMEDIATED

Authentication login rate limiting used `FAIL_OPEN`, allowing Redis failure to remove the credential-stuffing control.

**Remediation:** Authentication rate limiting now uses `FAIL_CLOSED`.

**Verification:** Security test suite passed after the change.

### SEC-003 — Stale security review claimed a global rate-limiting filter
**Severity:** MEDIUM  
**Status:** DOCUMENTATION CORRECTED

Repository inspection found authentication-specific Redis rate limiting, but no implementation matching the previously documented `RedisRateLimitingFilter` global control. The prior review also described a 10 MB production request limit while the hardened production profile uses 2 MB.

**Remediation:** This document records only controls verified in the current source tree. The implementation should not be represented as globally rate-limited until such a filter is actually implemented and tested.

### SEC-004 — Production Flyway auto-baselining
**Severity:** HIGH  
**Status:** REMEDIATED

The production profile previously enabled `baseline-on-migrate: true`. That behavior can silently establish a baseline against a non-empty database instead of forcing an explicit migration-history decision.

**Remediation:** Production now uses `baseline-on-migrate: false`. Baseline operations, when genuinely required, must be performed as an explicit controlled migration procedure.

### SEC-005 — CI security gates were not sufficient to protect main
**Severity:** HIGH  
**Status:** PARTIALLY REMEDIATED

The repository had security workflows, but `main` was not protected and had no required status checks at audit time.

**Remediation:** The hardened PR establishes passing CI/security checks. GitHub branch protection/ruleset configuration still requires an owner/admin action because the available repository integration does not expose a write operation for branch protection.

**Required repository policy:**
- Require pull requests before merging.
- Require the CI quality gate.
- Require the security quality gate.
- Require reproducible-build verification.
- Require conversation resolution/review as appropriate.
- Disable force pushes to `main`.
- Restrict deletion of `main`.

### SEC-006 — Supply-chain provenance was incomplete
**Severity:** MEDIUM  
**Status:** REMEDIATED IN RELEASE PIPELINE

The repository previously generated checksums but did not establish signed provenance for release artifacts.

**Remediation:** The production release workflow now builds a versioned container, generates an SBOM, and creates signed GitHub artifact/container attestations. GitHub documents artifact attestations as signed provenance linking an artifact to its workflow, repository, commit, and build context. 

SLSA Build L1 requires provenance describing how a package was built; higher levels increase provenance authenticity and build isolation. 

## Verified Security Controls

The hardened PR passed:

- Secret and credential scanning.
- Maven dependency vulnerability scanning.
- Container vulnerability scanning.
- Semgrep SAST.
- Aggregate security gate.
- Repository cleanliness and secret checks.
- Production configuration validation.
- Full Maven integration/unit test suite.
- Reproducible-build verification.

The security workflow therefore provides executable evidence for the above gates rather than relying solely on documentation.

## Residual Risks

### R-001 — Global API abuse protection
Authentication abuse is rate-limited. A separate, explicitly tested global/API-class rate limiting policy should be introduced before exposing high-value endpoints to hostile public traffic.

### R-002 — Production environment controls
TLS termination, database TLS verification, Kafka authentication/encryption, Redis authentication/TLS, secret-manager integration, network policy, WAF/API gateway policy, and cloud IAM remain deployment-environment concerns.

### R-003 — Disaster recovery evidence
Repository runbooks describe backup/restore and recovery procedures. Actual production readiness still requires an executed restore drill against the target infrastructure with measured RPO/RTO.

### R-004 — Capacity evidence
Phase 19 records 185 TPS sustainable capacity, 235 TPS observed peak load, and approximately 220–240 TPS saturation on a reference environment. These measurements must not be treated as universal cloud production limits.

### R-005 — Regulatory scope
This audit does not certify PCI DSS, SOC 2, ISO 27001, RBI authorization, money-transmitter licensing, or any other regulatory requirement. Those are separate organizational/legal assessments.

## Auditor Decision

**Code/repository gate:** READY_FOR_CONTROLLED_ROLLOUT after the hardened PR passes all mandatory checks.

**Unrestricted production traffic:** NOT YET CERTIFIED by this repository audit alone.

The correct next boundary is target-environment verification: deploy the exact attested release artifact to staging, execute smoke tests, migration validation, backup/restore verification, reconciliation checks, health/readiness checks, and controlled traffic tests before production exposure.
