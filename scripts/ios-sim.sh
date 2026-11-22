#!/usr/bin/env bash
# Compiles ios/TheatreSync.swiftpm with Xcode for the iOS Simulator, then installs and launches it on a
# simulated iPhone and iPad, checks it is still running after a few seconds (no crash at start), and
# saves screenshots. A small copy of each screenshot is printed in the log (base64 JPEG between markers)
# so it can be checked without downloading anything.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"; OUT="$ROOT/ios-shots"; mkdir -p "$OUT"
DD="${RUNNER_TEMP:-/tmp}/tsdd"
BUNDLE=com.theatresync.ios
xcodebuild -version
cd ios/TheatreSync.swiftpm
echo "== Schemes"; xcodebuild -list || true
echo "== Build (iOS Simulator)"
set +e
xcodebuild -scheme TheatreSync -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DD" \
  CODE_SIGNING_ALLOWED=NO build > "$OUT/build.log" 2>&1
RC=$?
set -e
grep -E "error:|warning: .*deprecated|BUILD (SUCCEEDED|FAILED)" "$OUT/build.log" | head -80 || true
[ $RC = 0 ] || { echo "iOS BUILD FAILED"; tail -60 "$OUT/build.log"; exit 1; }
echo "iOS BUILD SUCCEEDED"
APP=$(find "$DD/Build/Products" -maxdepth 3 -name "*.app" | head -1)
echo "App: $APP"
FAIL=0
for KIND in iPhone iPad; do
  UDID=$(xcrun simctl list devices available -j | python3 -c "
import json,sys
d=json.load(sys.stdin)['devices']
c=[x for k,v in d.items() if 'iOS' in k for x in v if x['name'].startswith('$KIND')]
c.sort(key=lambda x: x['name'])
print(c[-1]['udid'] if c else '')")
  [ -n "$UDID" ] || { echo "No $KIND simulator"; FAIL=1; continue; }
  echo "== $KIND ($UDID)"
  xcrun simctl boot "$UDID" || true
  xcrun simctl bootstatus "$UDID" -b
  xcrun simctl install "$UDID" "$APP"
  xcrun simctl launch "$UDID" "$BUNDLE"
  sleep 10
  xcrun simctl io "$UDID" screenshot "$OUT/$KIND.png"
  if xcrun simctl spawn "$UDID" launchctl list | grep -q "$BUNDLE"; then echo "PASS  $KIND: launched and still running after 10 s"
  else echo "FAIL  $KIND: not running after 10 s (crashed?)"; FAIL=1
    xcrun simctl spawn "$UDID" log show --last 1m --predicate "process == \"TheatreSync\"" --style compact 2>/dev/null | tail -40 || true; fi
  sips -Z 420 -s format jpeg -s formatOptions 55 "$OUT/$KIND.png" --out "$OUT/$KIND-small.jpg" >/dev/null
  echo "SCREENSHOT-$KIND-BEGIN"; base64 -b 1000 -i "$OUT/$KIND-small.jpg"; echo "SCREENSHOT-$KIND-END"
  xcrun simctl shutdown "$UDID" || true
done
[ $FAIL = 0 ] && echo "iOS simulator check passed" || { echo "iOS simulator check FAILED"; exit 1; }
