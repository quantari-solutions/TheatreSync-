#!/usr/bin/env bash
# STATIC checks on the iOS sources. This is NOT a compile: Linux cannot run
# the iOS SDK. Real compilation happens in Swift Playgrounds on the iPad (or Xcode).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/validate-ios-source.py
