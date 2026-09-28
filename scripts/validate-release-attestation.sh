#!/usr/bin/env bash
set -euo pipefail

SCRIPT="scripts/verify-release-attestation.sh"

if [[ ! -x "$SCRIPT" ]]; then
  chmod +x "$SCRIPT"
fi

if ! grep -Fq 'gh attestation verify "$IMAGE_REF"' "$SCRIPT"; then
  echo "[ERROR] attestation verification command missing"
  exit 1
fi

if ! grep -Fq '--repo "$REPOSITORY"' "$SCRIPT"; then
  echo "[ERROR] repository binding missing"
  exit 1
fi

if ! grep -Fq '--signer-workflow "$SIGNER_WORKFLOW"' "$SCRIPT"; then
  echo "[ERROR] signer workflow binding missing"
  exit 1
fi

if ! grep -Fq '--predicate-type "https://slsa.dev/provenance/v1"' "$SCRIPT"; then
  echo "[ERROR] SLSA provenance verification missing"
  exit 1
fi

if ! grep -Fq '--predicate-type "$SBOM_PREDICATE_TYPE"' "$SCRIPT"; then
  echo "[ERROR] SBOM predicate verification missing"
  exit 1
fi

if ! grep -Fq '@sha256:' "$SCRIPT"; then
  echo "[ERROR] immutable digest requirement missing"
  exit 1
fi

echo "[PASS] Release attestation verifier contains all mandatory enforcement controls."
