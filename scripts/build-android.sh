#!/usr/bin/env bash
# Builds an installable debug APK -> dist/TheatreSync-debug.apk
set -euo pipefail
source "$(dirname "$0")/_android-env.sh"
./gradlew --no-daemon :app:assembleDebug "$@"
mkdir -p "$REPO_ROOT/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$REPO_ROOT/dist/TheatreSync-debug.apk"
echo ">> APK: dist/TheatreSync-debug.apk"
