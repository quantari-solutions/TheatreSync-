# Project status

_Last updated: 2026-11-18. Milestone 1.4: crash fix, YouTube, screen mirroring, stop casting_

## 0.4.0 — field-test fixes (from the owner's media-room test)

| Report | Cause | Fix |
|---|---|---|
| App closes after choosing a movie | (1) MovieSession's `init` ran before `routerCallback` was initialised: a crash whenever a Cast session already existed at start-up, e.g. after Android restarted the app behind the file picker. (2) Any unexpected exception on a movie-server thread killed the whole app, so the TV sat on a spinner. | `init` moved last and deferred, with a regression test; server threads catch everything (tested with a failing file provider); sync engine fully kept by R8; on-device **crash reporter** with "Share crash report" |
| No way to stop the cast | Missing feature | **Stop casting** on the Now casting card and page (receiver STOP, tested against a fake Chromecast) |
| Wants YouTube and mirroring in the app | Missing features | **YouTube** page (paste or share a link → plays on the TV via YouTube's TV remote API, with fallback to the YouTube app) and **Mirror screen** page (opens Android's cast screen or Google Home). TheatreSync appears in YouTube's Share menu |


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

## New in 1.2

| Feature | Status | How it was checked |
|---|---|---|
| **Now casting** card + page: shows what any Chromecast is playing (YouTube title, channel, thumbnail; "Screen mirroring" for Chrome tabs), with play/pause/skip for YouTube | Built | Cast v2 protocol encoder/decoder and status parser unit-tested against real receiver payloads; end-to-end test against a fake Chromecast (idle → YouTube → Pause sent correctly). **Needs HW-05 on the real TV** |
| Headphones page: plain-language status, technical details behind ⓘ | Built | Paparazzi screenshots; emulator test that details are hidden until ⓘ is tapped |
| Back arrow + system back on inner pages, animated transitions, haptic taps | Built | Emulator tests for both back paths |

## Design

Both apps use the Quantari Solutions theme taken from quantari-solutions.vercel.app's CSS:
`#050508` background with a faint gold grid, gold gradient `#f0c44c → #ffe9a8 → #d4a017`,
Inter (bundled on Android, OFL licence in `android/third_party/`; iOS uses the system font),
pill buttons with a gold glow, `#0d0d16` cards with `#2a2410` borders. Android screens are
rendered to PNGs by Paparazzi and verified on every build.

## Bugs found and fixed in the audit

| Bug | Effect | Fix |
|---|---|---|
| Single "Sound early/late" tap ignored | 20 ms change was inside the 40 ms dead band | `forceCorrect()` on Android and iOS, with tests |
| Skip with unknown duration | ±10 s jumped to the start | Only clamp once the duration is known |
| Cast connection blip | Headphone sound ran on alone and drifted | Hold sound until the TV is back |
| App killed mid-movie | Room speakers left muted (YouTube silent next time) | Remember and undo our mute on next connect |
| VPN on | Chromecast given an unreachable address | Pick the real Wi-Fi interface, skip VPNs |
| Android 15 edge-to-edge | Content under the status bar | `enableEdgeToEdge()` + safe-area padding |
| Old Fragment from Cast library | File picker broken (lint fatal) | Pin Fragment 1.8.8 |
| Android 10/11 Bluetooth names | Names hidden wrongly | Only request BLUETOOTH_CONNECT on 12+ |
| Paused status | Showed "Waiting for picture" | Shows "Paused" / "Finished" |

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
