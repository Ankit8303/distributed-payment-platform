#!/usr/bin/env bash
set -euo pipefail

# Verify the cryptographically signed provenance and SBOM attestations for
# the exact immutable container digest before deployment.
#
# Usage:
#   ./scripts/verify-release-attestation.sh \
#     ghcr.io/Ankit8303/distributed-payment-platform@sha256:<digest>

IMAGE_REF="${1:-}"
REPOSITORY="${ATTESTATION_REPOSITORY:-Ankit8303/distributed-payment-platform}"
SIGNER_WORKFLOW="${ATTESTATION_SIGNER_WORKFLOW:-Ankit8303/distributed-payment-platform/.github/workflows/release.yml}"
SBOM_PREDICATE_TYPE="${ATTESTATION_SBOM_PREDICATE_TYPE:-https://spdx.dev/Document/v2.3}"

if [[ -z "$IMAGE_REF" ]]; then
  echo "[ERROR] image reference is required" >&2
  echo "Usage: $0 ghcr.io/Ankit8303/distributed-payment-platform@sha256:<digest>" >&2
  exit 2
fi

if [[ "$IMAGE_REF" != oci://* ]]; then
  IMAGE_REF="oci://$IMAGE_REF"
fi

if [[ "$IMAGE_REF" != *@sha256:* ]]; then
  echo "[ERROR] deployment verification requires an immutable sha256 image digest" >&2
  exit 2
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "[ERROR] GitHub CLI (gh) is required" >&2
  exit 2
fi

echo "[INFO] Verifying signed SLSA provenance attestation..."
gh attestation verify "$IMAGE_REF" \
  --repo "$REPOSITORY" \
  --signer-workflow "$SIGNER_WORKFLOW" \
  --predicate-type "https://slsa.dev/provenance/v1"

echo "[INFO] Verifying signed SBOM attestation..."
gh attestation verify "$IMAGE_REF" \
  --repo "$REPOSITORY" \
  --signer-workflow "$SIGNER_WORKFLOW" \
  --predicate-type "$SBOM_PREDICATE_TYPE"

echo "[PASS] Release attestation and SBOM verification succeeded for $IMAGE_REF"
