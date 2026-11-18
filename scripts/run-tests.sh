#!/usr/bin/env bash
# Runs everything that can be verified in the cloud. Hardware is NOT covered.
set -euo pipefail
cd "$(dirname "$0")"
echo "== Contracts ==";        ./validate-contracts.sh
echo "== iOS (static) ==";     ./validate-ios-source.sh
echo "== iOS shared code (compiled on Linux) =="; ./test-ios-shared.sh
echo "== Android ==";          ./test-android.sh
echo; echo "All cloud checks passed. Hardware features still need physical testing (docs/PHYSICAL-TESTING.md)."
