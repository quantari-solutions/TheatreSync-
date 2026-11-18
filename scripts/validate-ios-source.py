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

for rel in ["Package.swift", "TheatreSyncApp.swift", "Shared/SyncMessage.swift", "Shared/ClockSync.swift"]:
    if not (pkg / rel).is_file():
        errors.append(f"missing {rel}")

ALLOWED = {"SwiftUI", "Foundation", "Combine", "AVFoundation", "AVKit", "Network",
           "MediaPlayer", "PackageDescription", "AppleProductTypes", "os", "Observation"}
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

print(f"Checked {len(swift_files)} Swift file(s) (static only — NOT compiled).")
for e in errors:
    print("FAIL ", e)
print("OK" if not errors else f"{len(errors)} problem(s)")
sys.exit(1 if errors else 0)
