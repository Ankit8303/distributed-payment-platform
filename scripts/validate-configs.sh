#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Production Configuration & Container Validator
# Validates production configuration, Dockerfile, and docker-compose settings.
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "==> Validating Production Configurations and Container Definitions in: $REPO_ROOT"

ERRORS=0

# 1. Validate application-prod.yml exists
PROD_YML="$REPO_ROOT/src/main/resources/application-prod.yml"
if [[ ! -f "$PROD_YML" ]]; then
    echo " [ERROR] Missing src/main/resources/application-prod.yml"
    ERRORS=$((ERRORS + 1))
else
    # Verify show-sql is false
    if grep -E "show-sql:\s*true" "$PROD_YML" >/dev/null; then
        echo " [ERROR] application-prod.yml must have show-sql: false"
        ERRORS=$((ERRORS + 1))
    fi
    # Verify graceful shutdown is enabled
    if ! grep -E "shutdown:\s*graceful" "$PROD_YML" >/dev/null; then
        echo " [ERROR] application-prod.yml must enable server.shutdown: graceful"
        ERRORS=$((ERRORS + 1))
    fi
    # Verify sensitive actuator endpoints are not exposed
    if grep -E "include:\s*['\"]?\*['\"]?" "$PROD_YML" >/dev/null; then
        echo " [ERROR] application-prod.yml must not expose wildcard actuator endpoints ('*')"
        ERRORS=$((ERRORS + 1))
    fi
    # Reject explicit plaintext PostgreSQL connections in the production profile.
    # TLS certificate/hostname verification remains a target-environment requirement.
    if grep -Eiq 'jdbc:postgresql:[^[:space:]]*sslmode[[:space:]]*=[[:space:]]*disable' "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must not explicitly disable PostgreSQL TLS (sslmode=disable)"
        ERRORS=$((ERRORS + 1))
    fi
fi

# 2. Validate docker/Dockerfile
DOCKERFILE="$REPO_ROOT/docker/Dockerfile"
if [[ ! -f "$DOCKERFILE" ]]; then
    echo " [ERROR] Missing docker/Dockerfile"
    ERRORS=$((ERRORS + 1))
else
    if ! grep -E "^USER\s+appuser" "$DOCKERFILE" >/dev/null; then
        echo " [ERROR] docker/Dockerfile must run as non-root user (USER appuser)"
        ERRORS=$((ERRORS + 1))
    fi
    if ! grep -E "^HEALTHCHECK" "$DOCKERFILE" >/dev/null; then
        echo " [ERROR] docker/Dockerfile must define a HEALTHCHECK instruction"
        ERRORS=$((ERRORS + 1))
    fi
fi

# 3. Validate docker-compose.yml
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
if [[ ! -f "$COMPOSE_FILE" ]]; then
    echo " [ERROR] Missing docker-compose.yml"
    ERRORS=$((ERRORS + 1))
else
    # Check for healthchecks on critical services
    if ! grep -A 20 "postgres:" "$COMPOSE_FILE" | grep -q "healthcheck:"; then
        echo " [ERROR] docker-compose.yml missing healthcheck for postgres"
        ERRORS=$((ERRORS + 1))
    fi
    if ! grep -A 25 "kafka:" "$COMPOSE_FILE" | grep -q "healthcheck:"; then
        echo " [ERROR] docker-compose.yml missing healthcheck for kafka"
        ERRORS=$((ERRORS + 1))
    fi
    if ! grep -A 15 "redis:" "$COMPOSE_FILE" | grep -q "healthcheck:"; then
        echo " [ERROR] docker-compose.yml missing healthcheck for redis"
        ERRORS=$((ERRORS + 1))
    fi
fi

if [[ $ERRORS -gt 0 ]]; then
    echo "==> Configuration validation FAILED: $ERRORS issues detected."
    exit 1
else
    echo "==> Configuration validation PASSED: Production configurations and containers verified."
    exit 0
fi
