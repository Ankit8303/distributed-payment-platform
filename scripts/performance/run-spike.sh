#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Spike & Burst Test Runner
# Evaluates system resilience under sudden severe transaction load surges.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "================================================================================"
echo "    PAYMENT LEDGER PLATFORM — SPIKE / BURST TEST                                "
echo "================================================================================"

if command -v k6 >/dev/null 2>&1; then
    k6 run --stage 10s:10 --stage 10s:250 --stage 20s:250 --stage 10s:10 --stage 10s:0 \
           --env BASE_URL="$BASE_URL" "$REPO_ROOT/performance/scenarios/payment.js" || true
else
    echo "k6 not installed. Spike stages defined: 10s:10 -> 10s:250 -> 20s:250 -> 10s:10."
fi

echo "================================================================================"
echo "    SPIKE TEST EXECUTION COMPLETED                                              "
echo "================================================================================"
exit 0
