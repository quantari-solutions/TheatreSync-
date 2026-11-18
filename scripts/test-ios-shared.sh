#!/usr/bin/env bash
# Compiles the iOS app's shared Foundation-only code (ios/TheatreSync.swiftpm/Shared)
# with the Linux Swift toolchain and runs it against contracts/.
# This is a REAL compile + run of the protocol and sync logic, but it does NOT
# build the SwiftUI app (that needs Apple's SDK: Swift Playgrounds or Xcode).
set -euo pipefail
cd "$(dirname "$0")/.."
SWIFTC="${SWIFTC:-$(command -v swiftc || echo "$HOME/swift/swift-6.1.2-RELEASE-ubuntu24.04/usr/bin/swiftc")}"
if [ ! -x "$SWIFTC" ]; then
  echo "swiftc not found (install Swift for Linux or set SWIFTC)"
  [ "${REQUIRE_SWIFT:-0}" = "1" ] && exit 1
  echo "skipping"; exit 0
fi
out="$(mktemp -d)"
"$SWIFTC" -swift-version 5 -O -o "$out/shared-tests" ios/TheatreSync.swiftpm/Shared/*.swift ios/SharedTests/main.swift
"$out/shared-tests" contracts
