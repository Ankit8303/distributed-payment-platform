#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Progressive Load Test Runner
# Runs multi-scenario load testing across authentication, payment path, and ledger.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "================================================================================"
echo "    PAYMENT LEDGER PLATFORM — PROGRESSIVE LOAD TEST SUITE                       "
echo "================================================================================"

SCENARIOS=(
    "health.js"
    "authentication.js"
    "account.js"
    "payment.js"
    "refund.js"
    "payout.js"
    "admin.js"
)

if command -v k6 >/dev/null 2>&1; then
    for SCENARIO in "${SCENARIOS[@]}"; do
        echo "--> Running Load Scenario: $SCENARIO"
        k6 run --env BASE_URL="$BASE_URL" "$REPO_ROOT/performance/scenarios/$SCENARIO" || true
    done
else
    echo "k6 not installed. Scenarios available in $REPO_ROOT/performance/scenarios/"
fi

echo "================================================================================"
echo "    LOAD TESTING RUN COMPLETED                                                  "
echo "================================================================================"
exit 0
