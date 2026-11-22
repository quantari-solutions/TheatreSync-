#!/usr/bin/env python3
"""Static (non-compiling) checks for ios/TheatreSync.swiftpm.

1. Required Swift Playgrounds package files exist.
2. Only frameworks known to work in Swift Playgrounds are imported.
3. Braces/parentheses balance in every .swift file (catches truncated files).
4. Protocol constants in Swift match the JSON schema (version, message types, enums).
"""
import json, re, sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
pkg = root / "ios" / "TheatreSync.swiftpm"
schema = json.loads((root / "contracts/schemas/sync-message.v1.schema.json").read_text())
errors = []

for rel in ["Package.swift", "TheatreSyncApp.swift", "JoinView.swift", "ListeningView.swift", "ListenerSession.swift",
            "HostBrowser.swift", "DiagnosticsView.swift", "Shared/SyncMessage.swift", "Shared/ClockSync.swift", "Shared/Sync.swift"]:
    if not (pkg / rel).is_file():
        errors.append(f"missing {rel}")

ALLOWED = {"SwiftUI", "Foundation", "Combine", "AVFoundation", "AVKit", "Network",
           "MediaPlayer", "UIKit", "PackageDescription", "AppleProductTypes", "os", "Observation",
           "CryptoKit", "WebKit"}
swift_files = sorted(pkg.rglob("*.swift"))
for f in swift_files:
    src = f.read_text()
    for mod in re.findall(r"^\s*import\s+(\w+)", src, re.M):
        if mod not in ALLOWED:
            errors.append(f"{f.relative_to(root)}: import {mod} not on the Swift Playgrounds allow-list")
    # strip strings & comments before counting brackets
    code = re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/', "", src)
    for o, c in ["{}", "()", "[]"]:
        if code.count(o) != code.count(c):
            errors.append(f"{f.relative_to(root)}: unbalanced {o}{c} ({code.count(o)} vs {code.count(c)})")

msg = (pkg / "Shared/SyncMessage.swift").read_text() if (pkg / "Shared/SyncMessage.swift").exists() else ""
m = re.search(r"protocolVersion\s*=\s*(\d+)", msg)
if not m or int(m.group(1)) != schema["properties"]["v"]["const"]:
    errors.append("Swift protocolVersion does not match schema 'v'")

def swift_cases(enum_name):
    block = re.search(r"enum\s+%s\b[^{]*\{([\s\S]*?)\n\s*\}" % enum_name, msg)
    if not block:
        return None
    vals = set()
    for name, raw in re.findall(r'case\s+`?(\w+)`?(?:\s*=\s*"([^"]+)")?', block.group(1)):
        vals.add(raw or name)
    return vals

checks = {
    "MessageType": set(schema["properties"]["type"]["enum"]),
    "Platform": set(schema["$defs"]["hello"]["properties"]["platform"]["enum"]),
    "Role": set(schema["$defs"]["hello"]["properties"]["role"]["enum"]),
    "PlaybackStatus": set(schema["$defs"]["playbackState"]["properties"]["state"]["enum"]),
    "OutputRoute": set(schema["$defs"]["latencyReport"]["properties"]["outputRoute"]["enum"]),
    "ErrorCode": set(schema["$defs"]["error"]["properties"]["code"]["enum"]),
}
for enum, expected in checks.items():
    got = swift_cases(enum)
    if got is None:
        errors.append(f"Swift enum {enum} not found")
    elif got != expected:
        errors.append(f"Swift enum {enum} {sorted(got)} != schema {sorted(expected)}")

# 5. Swift sync constants must equal the Kotlin DriftCorrector defaults.
kt = (root / "android/sync-core/src/main/kotlin/com/theatresync/core/DriftCorrector.kt").read_text()
sw = (pkg / "Shared/Sync.swift").read_text() if (pkg / "Shared/Sync.swift").exists() else ""
for name in ["startCorrectingMs", "stopCorrectingMs", "seekThresholdMs", "maxSpeedDelta", "catchUpMs"]:
    k = re.search(r"%s:\s*\w+\s*=\s*([\d_.]+)" % name, kt)
    w = re.search(r"var %s:\s*\w+\s*=\s*([\d_.]+)" % name, sw)
    if not k or not w or float(k.group(1).replace("_", "")) != float(w.group(1).replace("_", "")):
        errors.append(f"DriftCorrector.{name} differs between Kotlin and Swift")

# 6. Bonjour service type must match what Android announces.
ann = (root / "android/app/src/main/kotlin/com/theatresync/app/HostAnnouncer.kt").read_text()
svc = re.search(r'SERVICE_TYPE = "([^"]+)"', ann).group(1)
for rel in ["HostBrowser.swift", "Package.swift"]:
    if svc not in (pkg / rel).read_text():
        errors.append(f"{rel} does not use Bonjour type {svc}")

print(f"Checked {len(swift_files)} Swift file(s) (static only — NOT compiled).")
for e in errors:
    print("FAIL ", e)
print("OK" if not errors else f"{len(errors)} problem(s)")
sys.exit(1 if errors else 0)
