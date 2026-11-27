#!/usr/bin/env bash
# Publishes dist/TheatreSync.apk as the newest in-app update (Settings → About → Update).
# Only when the owner says "release". Usage:
#   scripts/publish-release.sh <folder> "What's new, in plain words" [base URL]
# <folder>: a checkout of quantari-solutions/theatresync-releases (default base URL), or this repo's releases/
# folder if the code repository is public (base URL https://raw.githubusercontent.com/quantari-solutions/TheatreSync-/main/releases).
# Then commit + push that checkout. The app reads latest.json from it; nothing else is public.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
source scripts/_android-env.sh
cd "$root"
repo="$(cd "${1:?folder to publish into}" && pwd)"; notes="${2:-}"
base="${3:-https://raw.githubusercontent.com/quantari-solutions/theatresync-releases/main}"
apk=dist/TheatreSync.apk
[ -f "$apk" ] || { echo "Build first: scripts/build-android.sh"; exit 1; }

# Updates only install over the owner's app if signed with the same key: refuse anything else.
signer=$(ls -d "$ANDROID_HOME"/build-tools/*/ | tail -1)apksigner
cert=$("$signer" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
want=$(cat scripts/release-signing-cert.sha256)
[ "$cert" = "$want" ] || { echo "REFUSED: APK signed with $cert, releases must use $want"; exit 1; }

ver=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts)
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' android/app/build.gradle.kts)
# The APK must be the version being announced (owner, 2026-11-27: a stale 0.7.8 file published as "0.7.9"
# installed over itself and the app offered the same update forever). Rebuild if it doesn't match.
badging=$("$(dirname "$signer")"/aapt2 dump badging "$apk" | sed -n 1p)
case "$badging" in *"versionCode='$code'"*"versionName='$ver'"*) ;;
  *) echo "REFUSED: $apk is not $ver ($code) — run scripts/build-android.sh first. It is: $badging"; exit 1;; esac
name="TheatreSync-$ver.apk"
rm -f "$repo"/TheatreSync-*.apk
cp "$apk" "$repo/$name"
sha=$(sha256sum "$repo/$name" | cut -d' ' -f1)
size=$(stat -c%s "$repo/$name")
python3 - "$repo/latest.json" "$code" "$ver" "$name" "$sha" "$size" "$notes" "$base" <<'PY'
import json, sys
out, code, ver, name, sha, size, notes, base = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": ver,
           "apkUrl": f"{base}/{name}",
           "sha256": sha, "sizeBytes": int(size), "notes": notes}, open(out, "w"), indent=2)
PY
echo ">> $name ($size bytes, sha256 $sha) and latest.json ready in $repo — commit and push it."
