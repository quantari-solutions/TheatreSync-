#!/usr/bin/env python3
"""Owner's rule: the iOS app is a copy of the Android app, and nothing they asked for silently disappears.
Fails if an Android page has no iOS counterpart (or a written reason it can't exist on iOS), if a
required iOS feature is missing, or if a page other than Home hides its back button."""
import re, sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
android = (root / "android/app/src/main/kotlin/com/theatresync/app/MainActivity.kt").read_text()
ios_dir = root / "ios/TheatreSync.swiftpm"
ios = {f.name: f.read_text() for f in ios_dir.rglob("*.swift")}
all_ios = "\n".join(ios.values())
errors = []

# Android page -> iOS page (Page enum case), or "n/a: why".
PAGES = {
    "HOME": "home", "NOW_CASTING": "nowCasting", "YOUTUBE": "youtube", "REMOTE": "remote",
    "SETTINGS": "settings", "MOVIE": "movie", "HEADPHONES": "headphones", "HEADPHONE_SETTINGS": "headphones",
    "EXPERIMENTAL": "experimental", "MULTI_AUDIO": "multiAudio", "MIRROR": "mirror",
    "CRASHES": "n/a: iOS shows its own crash reports in Settings → Privacy → Analytics",
    "REMOTE_CAST": "remoteCast",
    "GITHUB": "n/a: crash-report upload to GitHub needs a token stored on the phone; Android only",
}
m = re.search(r"enum class Page \{([^}]*)\}", android)
android_pages = [p.strip() for p in m.group(1).split(",") if p.strip()] if m else []
if not android_pages: errors.append("couldn't read Android's Page enum")
ios_enum = re.search(r"enum Page: Hashable \{\s*case ([^\n]*)", all_ios)
ios_pages = {c.strip() for c in ios_enum.group(1).split(",")} if ios_enum else set()
for p in android_pages:
    target = PAGES.get(p)
    if target is None: errors.append(f"Android page {p} has no iOS counterpart listed in scripts/ios-parity-check.py")
    elif target not in ("home",) and not target.startswith("n/a") and target not in ios_pages:
        errors.append(f"Android page {p} → iOS page .{target} is missing")

# Features the owner asked for that must stay in the iOS app (symbol → what it is).
FEATURES = {
    "OpeningView": "opening animation on a fresh start",
    "func showTitleCard": "living title card / screensaver on the TV",
    "class YouTubeCaster": "YouTube casting",
    "class SamsungFinder": "The Frame (Samsung) discovery",
    "samsungIp": "Samsung TV address in Settings",
    "class HeadphonesPlayer": "TV sound in headphones (YouTube)",
    "struct TapInCard": "TapIn QR for guests",
    "class MovieCaster": "movie night: TV picture + headphone sound",
    "class NetworkPower": "projector & receiver on/off",
    "class UpdateCheck": "update notice in About",
    "Loading…": "seek bar keeps the dragged spot with Loading…",
    "line.3.horizontal": "☰ menu",
    "signatureIntro": "Signature intro toggle",
    "ytAway": "YouTube to a home TV from anywhere (remembered TVs)",
    "remoteCast": "Remote cast switch in Experimental",
    "struct OrbitCard": "moving gold glow on the Now casting card while playing",
    "func stop()": "Stop for remote / Samsung YouTube casts",
}
for sym, what in FEATURES.items():
    if sym not in all_ios: errors.append(f"iOS is missing: {what} ({sym})")

# Every page except Home keeps its back button.
for name, src in ios.items():
    if ".toolbar(.hidden, for: .navigationBar)" in src and name != "HomeView.swift":
        errors.append(f"{name} hides the navigation bar (no back button)")
# No manual sync buttons (Android: auto sync only).
if "adjustSoundDelay" in all_ios: errors.append("manual sound-delay buttons are back (Android uses automatic sync only)")

if errors:
    print("iOS parity check FAILED:"); [print("  -", e) for e in errors]; sys.exit(1)
print(f"iOS parity check OK: {len(android_pages)} Android pages covered, {len(FEATURES)} features present, back buttons intact")
