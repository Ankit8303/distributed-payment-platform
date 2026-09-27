#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Automated Secret & Credential Scanner
# Scans tracked repository files for committed credentials, private keys,
# API keys, and sensitive tokens. Exits with code 1 if unmasked secrets are found.
# ==============================================================================
set -euo pipefail

SCAN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "==> Running Secret Scanner on: $SCAN_DIR"

FAILURES=0

# Define patterns to scan for (excluding safe placeholders and test templates)
PATTERNS=(
    "AKIA[0-9A-Z]{16}"                                    # AWS Access Key ID
    "-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----" # PEM Private Key
    "https://hooks\.slack\.com/services/T[0-9A-Za-z_]+/B[0-9A-Za-z_]+/[0-9A-Za-z_]+" # Slack Webhook
    "gh[pousr]_[0-9a-zA-Z]{36}"                          # GitHub Personal Access Token
    "sk_live_[0-9a-zA-Z]{24,}"                           # Stripe Live Secret Key
    "xox[baprs]-[0-9a-zA-Z]{10,48}"                       # Slack token
)

# Files to exclude from secret scanning
EXCLUDE_ARGS=(
    "--exclude-dir=.git"
    "--exclude-dir=target"
    "--exclude-dir=.idea"
    "--exclude-dir=.vscode"
    "--exclude-dir=node_modules"
    "--exclude-dir=test"
    "--exclude-dir=src/test"
    "--exclude=*.class"
    "--exclude=*.jar"
    "--exclude=*.png"
    "--exclude=*.svg"
    "--exclude=*.log"
    "--exclude=scan-secrets.*"
    "--exclude=*.md"
)

for PATTERN in "${PATTERNS[@]}"; do
    echo "Scanning pattern: $PATTERN"
    MATCHES=$(grep -rEn "${EXCLUDE_ARGS[@]}" "$PATTERN" "$SCAN_DIR" 2>/dev/null || true)
    if [[ -n "$MATCHES" ]]; then
        # Check if matches are just test strings or false positives
        echo " [ALERT] Potential secret leak found for pattern: $PATTERN"
        echo "$MATCHES"
        FAILURES=$((FAILURES + 1))
    fi
done

# Check for unmasked live passwords in application.yml or application-prod.yml
if grep -E "^[[:space:]]*(password|secret):[[:space:]]+['\"][^$\{\}]+['\"]" "$SCAN_DIR/src/main/resources/application-prod.yml" 2>/dev/null; then
    echo " [ALERT] Hardcoded plain password/secret found in application-prod.yml"
    FAILURES=$((FAILURES + 1))
fi

if [[ $FAILURES -gt 0 ]]; then
    echo "==> Secret scan FAILED: $FAILURES secret patterns detected."
    exit 1
else
    echo "==> Secret scan PASSED: 0 secrets detected."
    exit 0
fi
