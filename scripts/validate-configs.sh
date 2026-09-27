#!/usr/bin/env bash
# ==============================================================================
# Payment Ledger Platform — Production Configuration & Container Validator
# Validates production configuration, Dockerfile, and docker-compose settings.
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "==> Validating Production Configurations and Container Definitions in: $REPO_ROOT"

ERRORS=0

# 1. Validate application-prod.yml
PROD_YML="$REPO_ROOT/src/main/resources/application-prod.yml"
if [[ ! -f "$PROD_YML" ]]; then
    echo " [ERROR] Missing src/main/resources/application-prod.yml"
    ERRORS=$((ERRORS + 1))
else
    if grep -E "show-sql:\s*true" "$PROD_YML" >/dev/null; then
        echo " [ERROR] application-prod.yml must have show-sql: false"
        ERRORS=$((ERRORS + 1))
    fi
    if ! grep -E "shutdown:\s*graceful" "$PROD_YML" >/dev/null; then
        echo " [ERROR] application-prod.yml must enable server.shutdown: graceful"
        ERRORS=$((ERRORS + 1))
    fi
    if grep -Fq 'include: "*"' "$PROD_YML" || grep -Fq "include: '*'" "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must not expose wildcard actuator endpoints"
        ERRORS=$((ERRORS + 1))
    fi

    # Reject explicit plaintext PostgreSQL connections.
    if grep -Eiq 'jdbc:postgresql:[^[:space:]]*sslmode[[:space:]]*=[[:space:]]*disable' "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must not explicitly disable PostgreSQL TLS (sslmode=disable)"
        ERRORS=$((ERRORS + 1))
    fi

    # Production Kafka must never use plaintext transport.
    if grep -Eq '^[[:space:]]*security.protocol:[[:space:]]*(PLAINTEXT|SASL_PLAINTEXT)[[:space:]]*$' "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must not use plaintext Kafka transport (PLAINTEXT/SASL_PLAINTEXT)"
        ERRORS=$((ERRORS + 1))
    fi

    # Production Kafka security protocol must be explicitly supplied by the environment.
    if ! grep -Fq 'security.protocol: ${KAFKA_SECURITY_PROTOCOL}' "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must require KAFKA_SECURITY_PROTOCOL for Kafka"
        ERRORS=$((ERRORS + 1))
    fi

    # Production Redis must explicitly enable TLS.
    if ! grep -Fq "ssl:" "$PROD_YML" || ! grep -Fq "enabled: true" "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must explicitly enable Redis TLS"
        ERRORS=$((ERRORS + 1))
    fi

    # Production Redis authentication must be supplied by the environment.
    if ! grep -Fq 'password: ${REDIS_PASSWORD}' "$PROD_YML"; then
        echo " [ERROR] application-prod.yml must require REDIS_PASSWORD for Redis authentication"
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
