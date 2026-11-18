#!/usr/bin/env bash
# Builds an installable debug APK -> dist/TheatreSync-debug.apk
set -euo pipefail
source "$(dirname "$0")/_android-env.sh"
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease "$@"
mkdir -p "$REPO_ROOT/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$REPO_ROOT/dist/TheatreSync-debug.apk"
# Optimised (R8) build: smaller and smoother. This is the one to install for real use.
cp app/build/outputs/apk/release/app-release.apk "$REPO_ROOT/dist/TheatreSync.apk"
echo ">> APKs: dist/TheatreSync.apk (optimised, install this) and dist/TheatreSync-debug.apk"
