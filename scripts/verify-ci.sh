#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Master Local CI Quality Gate Runner
# Runs all pre-flight quality gates, security scans, and build validations
# locally before pushing commits or creating pull requests.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "================================================================================"
echo "    PAYMENT LEDGER PLATFORM — LOCAL CI QUALITY GATE RUNNER (PHASE 18)           "
echo "================================================================================"

# Gate 1: Ensure no .env file
echo "--> Gate 1: Checking for uncommitted .env files..."
if [[ -f "$REPO_ROOT/.env" ]]; then
    echo "[FAIL] .env file detected in repository root. Remove before committing."
    exit 1
fi
echo "[PASS] No .env file present."

# Gate 2: Secret scanning
echo "--> Gate 2: Running secret scanning..."
bash "$SCRIPT_DIR/scan-secrets.sh"

# Gate 3: Migration validation
echo "--> Gate 3: Validating Flyway migrations..."
bash "$SCRIPT_DIR/validate-migrations.sh"

# Gate 4: Production configuration & container validation
echo "--> Gate 4: Validating configuration and container hardening..."
bash "$SCRIPT_DIR/validate-configs.sh"

# Gate 5: Reproducible build verification
echo "--> Gate 5: Checking reproducible build configuration..."
bash "$SCRIPT_DIR/verify-reproducibility.sh"

# Gate 6: Maven compile and test suite
if [[ "${1:-}" != "--skip-maven-test" && "${1:-}" != "-SkipMavenTest" ]]; then
    echo "--> Gate 6: Executing Maven verify & test suite..."
    chmod +x "$REPO_ROOT/mvnw"
    "$REPO_ROOT/mvnw" -B verify
fi

echo "================================================================================"
echo "    ALL CI QUALITY GATES PASSED (100% SUCCESS) — READY FOR PUSH / PR           "
echo "================================================================================"
exit 0
