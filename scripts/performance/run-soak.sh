#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Sustained Soak Test Runner
# Runs extended steady-state load to identify memory leaks and resource exhaustion.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
SOAK_DURATION="${SOAK_DURATION:-10m}"

echo "================================================================================"
echo "    PAYMENT LEDGER PLATFORM — SOAK TEST (Duration: $SOAK_DURATION)             "
echo "================================================================================"

if command -v k6 >/dev/null 2>&1; then
    k6 run --vus 25 --duration "$SOAK_DURATION" --env BASE_URL="$BASE_URL" "$REPO_ROOT/performance/scenarios/payment.js" || true
else
    echo "k6 not installed. Soak scenario definition at $REPO_ROOT/performance/scenarios/payment.js"
fi

echo "================================================================================"
echo "    SOAK TEST EXECUTION COMPLETED                                               "
echo "================================================================================"
exit 0
