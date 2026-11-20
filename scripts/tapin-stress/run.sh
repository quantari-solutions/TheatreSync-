#!/usr/bin/env bash
# TapIn listening-page stress test in Chromium (Playwright): pauses, jumps, slow Wi-Fi, dropped
# connections, a host clock reset, hidden page. Not part of CI (needs ffmpeg + Playwright).
# Usage: scripts/tapin-stress/run.sh [sound.mp4]   (default: a 5-minute 5.1 AAC test tone)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; mkdir -p "$here/build"
python3 - "$here/build/listen.html" <<'PY'
import sys, textwrap
s = open('android/sync-core/src/main/kotlin/com/theatresync/core/ListenPage.kt').read()
html = s.split('val HTML: String = """', 1)[1].split('""".trimIndent()', 1)[0]
open(sys.argv[1], 'w').write(textwrap.dedent(html).strip() + "\n")
PY
sound="${1:-$here/build/tone.mp4}"
[ -f "$sound" ] || ffmpeg -loglevel error -y -f lavfi -i "sine=f=440:d=300:sample_rate=48000" \
  -af "pan=5.1|FL=c0|FR=c0|FC=c0|LFE=c0|BL=c0|BR=c0" -c:a aac -b:a 384k "$sound"
python3 "$here/server.py" "$sound" & srv=$!; trap 'kill $srv' EXIT; sleep 1
node "$here/stress.js"
node "$here/guests.js"
