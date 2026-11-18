# Project status

_Last updated: 2026-11-18. Milestone 1: movie night for your own files_

## Audit: what actually works

| Feature | Status | How it was checked |
|---|---|---|
| Choose movie → choose Chromecast → start (Android) | Built | Compiles, lint 0 issues; setup screen tested on an Android 14 emulator |
| Phone serves the movie to the Chromecast (HTTP with byte ranges) | Tested | JVM tests + on-device emulator test |
| Headphone sound follows the TV position (speed nudges / seek) | Tested | 100-second closed-loop simulation (JVM + Swift), and **real audio playback on the emulator** locking onto a simulated TV clock within 80 ms |
| Clock sync between phones over Wi-Fi | Tested | JVM test over real HTTP; identical Swift code compiled and tested on Linux |
| Protocol shared by Android and iOS | Tested | Schema validation; Kotlin and Swift round-trip every fixture |
| iPhone/iPad joins and plays synced sound | Built, **not compiled** | Shared logic compiled + tested on Linux; SwiftUI/AVFoundation/Bonjour parts only statically checked |
| Mute room speakers, pause on headphone disconnect / phone call, screen-off playback | Built | Compiles; needs a real phone |
| **Chromecast actually plays the movie** | **Not verified** | Needs HW-03 |
| **Bluetooth headphones + lip sync** | **Not verified** | Needs HW-03 |
| **Friend's iPhone in sync** | **Not verified** | Needs HW-04 |
| Netflix / streaming apps | **Not possible in software** | See docs/STREAMING-APPS.md (hardware options) |

What the cloud sandbox cannot do: run an Android emulator (no virtualisation here; GitHub
Actions runs it instead), talk to a real Chromecast or Bluetooth device, or build iOS apps.

## Next physical tests (in order)

1. **HW-03**: movie night on the Android phone with the XM4 ([steps](docs/PHYSICAL-TESTING.md)).
2. **HW-04**: friend joins on the iPhone (first real iOS compile).
3. HW-01 / HW-02 (headphone check screens) are useful if HW-03/04 fail.

## Open questions for the owner

* **Chromecast and AV receiver model numbers.** They were in the original prompt, but only section 17 reached Claude. Needed for the streaming-app advice.
* Sections 1–16 of the original brief (paste them, or add as `docs/PRODUCT-BRIEF.md`).

## Possible next steps

* iPad/iPhone as the host (send to the Chromecast without the Android phone). Needs either a Mac/Xcode for Google's Cast library, or a hand-written Cast connection.
* Remember the sound delay per pair of headphones.
* Pause/skip from the iPhone.

## Working agreement

Claude merges PRs itself once all checks pass (see [CLAUDE.md](CLAUDE.md)).
