#!/usr/bin/env bash
# Installs the Android SDK command-line tools + the packages TheatreSync needs.
# Idempotent. Works in Claude Code Cloud, GitHub Actions and the devcontainer.
# No Android Studio required.
set -euo pipefail

CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-11076708}"   # cmdline-tools 12.0
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"

export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo ">> Downloading Android command-line tools to $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo ">> Accepting licenses"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

echo ">> Installing $PLATFORM $BUILD_TOOLS platform-tools"
"$SDKMANAGER" --install "$PLATFORM" "$BUILD_TOOLS" "platform-tools" >/dev/null

# Tell Gradle where the SDK is (local.properties is git-ignored).
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
echo "sdk.dir=$ANDROID_HOME" > "$repo_root/android/local.properties"

echo ">> Android SDK ready at $ANDROID_HOME"
