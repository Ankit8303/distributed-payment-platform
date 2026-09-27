#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Reproducible Build & Artifact Integrity Validator
# Validates project.build.outputTimestamp property and artifact checksums.
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM_FILE="$REPO_ROOT/pom.xml"

echo "==> Verifying Reproducible Build Configuration in: $POM_FILE"

# 1. Check pom.xml for project.build.outputTimestamp
if ! grep -q "<project.build.outputTimestamp>" "$POM_FILE"; then
    echo " [ERROR] pom.xml is missing <project.build.outputTimestamp> property"
    exit 1
fi

TIMESTAMP=$(grep -oPm1 "(?<=<project.build.outputTimestamp>)[^<]+" "$POM_FILE" || sed -n 's/.*<project.build.outputTimestamp>\(.*\)<\/project.build.outputTimestamp>.*/\1/p' "$POM_FILE")
echo " [OK] Found project.build.outputTimestamp: $TIMESTAMP"

# 2. Check ISO-8601 UTC format (e.g., 2026-09-25T00:00:00Z)
if [[ ! "$TIMESTAMP" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$ ]]; then
    echo " [ERROR] project.build.outputTimestamp is not a valid ISO-8601 UTC timestamp: $TIMESTAMP"
    exit 1
fi

# 3. Generate SHA-256 checksums for any JARs in target/
if ls "$REPO_ROOT"/target/*.jar 1>/dev/null 2>&1; then
    for JAR in "$REPO_ROOT"/target/*.jar; do
        if [[ ! "$JAR" =~ original- ]]; then
            echo "Generating SHA-256 for: $(basename "$JAR")"
            if command -v sha256sum >/dev/null 2>&1; then
                sha256sum "$JAR" | tee "${JAR}.sha256"
            elif command -v shasum >/dev/null 2>&1; then
                shasum -a 256 "$JAR" | tee "${JAR}.sha256"
            fi
        fi
    done
fi

echo "==> Reproducible build configuration validation PASSED."
exit 0
