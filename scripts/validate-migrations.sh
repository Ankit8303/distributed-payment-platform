#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Flyway Migration Integrity Validator
# Validates migration filename conventions, monotonically increasing versions,
# absence of duplicate version numbers, and file non-emptiness.
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS_DIR="$REPO_ROOT/src/main/resources/db/migration"

echo "==> Validating Flyway migrations in: $MIGRATIONS_DIR"

if [[ ! -d "$MIGRATIONS_DIR" ]]; then
    echo "ERROR: Migrations directory does not exist: $MIGRATIONS_DIR"
    exit 1
fi

ERRORS=0
VERSIONS=()

for FILEPATH in "$MIGRATIONS_DIR"/*.sql; do
    FILENAME=$(basename "$FILEPATH")
    
    # Verify file pattern V<number>__<description>.sql
    if [[ ! "$FILENAME" =~ ^V([0-9]+)__[a-zA-Z0-9_]+\.sql$ ]]; then
        echo " [ERROR] Invalid migration filename format: $FILENAME (must match V<digits>__<description>.sql)"
        ERRORS=$((ERRORS + 1))
        continue
    fi
    
    VERSION="${BASH_REMATCH[1]}"
    
    # Check for non-empty file
    if [[ ! -s "$FILEPATH" ]]; then
        echo " [ERROR] Migration file is empty: $FILENAME"
        ERRORS=$((ERRORS + 1))
    fi
    
    # Check for forbidden dangerous statements
    if grep -iq "DROP DATABASE" "$FILEPATH"; then
        echo " [ERROR] Forbidden 'DROP DATABASE' statement found in: $FILENAME"
        ERRORS=$((ERRORS + 1))
    fi
    
    VERSIONS+=("$VERSION")
done

# Sort versions numerically
SORTED_VERSIONS=($(printf '%s\n' "${VERSIONS[@]}" | sort -n))

# Check for duplicate versions and monotonic sequence
PREV_VER=0
for VER in "${SORTED_VERSIONS[@]}"; do
    VER_INT=$((10#$VER))
    if [[ $VER_INT -eq $PREV_VER ]]; then
        echo " [ERROR] Duplicate migration version detected: V$VER"
        ERRORS=$((ERRORS + 1))
    elif [[ $VER_INT -ne $((PREV_VER + 1)) ]]; then
        echo " [WARNING/ERROR] Non-consecutive migration version: expected V$((PREV_VER + 1)), got V$VER_INT"
        ERRORS=$((ERRORS + 1))
    fi
    PREV_VER=$VER_INT
done

echo "Validated ${#SORTED_VERSIONS[@]} Flyway migrations (V1 through V$PREV_VER)."

if [[ $ERRORS -gt 0 ]]; then
    echo "==> Migration validation FAILED: $ERRORS issues detected."
    exit 1
else
    echo "==> Migration validation PASSED: All migrations comply with integrity rules."
    exit 0
fi
