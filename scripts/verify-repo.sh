#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test ! -f "$SCRIPT_DIR/../.env" || { echo "Do not commit .env"; exit 1; }
bash "$SCRIPT_DIR/scan-secrets.sh"
