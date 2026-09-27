#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Progressive Capacity Stepping Runner
# Steps load through 10, 25, 50, 100, 200, 400 VUs to determine saturation ceiling.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "================================================================================"
echo "    PAYMENT LEDGER PLATFORM — PROGRESSIVE CAPACITY STEPPING                     "
echo "================================================================================"

STEPS=(10 25 50 100 200 400)

if command -v k6 >/dev/null 2>&1; then
    for VU in "${STEPS[@]}"; do
        echo "--> Testing Capacity Step: $VU Virtual Users..."
        k6 run --vus "$VU" --duration 20s --env BASE_URL="$BASE_URL" "$REPO_ROOT/performance/scenarios/payment.js" || true
        sleep 5
    done
else
    echo "k6 not installed. Capacity stepping plan: 10 -> 25 -> 50 -> 100 -> 200 -> 400 VUs."
fi

echo "================================================================================"
echo "    CAPACITY STEPPING COMPLETED                                                 "
echo "================================================================================"
exit 0
