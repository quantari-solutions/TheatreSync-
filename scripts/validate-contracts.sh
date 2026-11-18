#!/usr/bin/env bash
# Validates shared JSON protocol contracts used by Android and iOS.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -c "import jsonschema" 2>/dev/null || python3 -m pip install --quiet "jsonschema>=4.20"
python3 scripts/validate-contracts.py
