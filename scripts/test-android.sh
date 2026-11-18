#!/usr/bin/env bash
# Unit tests (JVM) + Android lint. Proves logic and manifest correctness, NOT hardware behaviour.
set -euo pipefail
source "$(dirname "$0")/_android-env.sh"
# verifyPaparazziDebug runs all app unit tests AND compares every screen to the recorded screenshots.
./gradlew --no-daemon :sync-core:test :app:verifyPaparazziDebug :app:lintDebug "$@"
echo ">> Reports: android/*/build/reports/"
