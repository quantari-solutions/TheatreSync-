#!/usr/bin/env bash
# Unit tests (JVM) + Android lint. Proves logic and manifest correctness, NOT hardware behaviour.
set -euo pipefail
source "$(dirname "$0")/_android-env.sh"
./gradlew --no-daemon :sync-core:test :app:testDebugUnitTest :app:lintDebug "$@"
echo ">> Reports: android/*/build/reports/"
