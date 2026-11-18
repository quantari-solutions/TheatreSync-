# Project status

_Last updated: 2026-11-18 — Milestone 0 (foundation)_

## Done (cloud-verified)

| Item | Evidence |
|---|---|
| Repo structure, scripts, devcontainer | `scripts/`, `.devcontainer/` |
| Protocol v1 JSON schema + 6 valid / 5 invalid fixtures | `./scripts/validate-contracts.sh` — 11/11 ok |
| Android `sync-core`: codec, clock sync, playback clock, latency aligner | 17 JVM unit tests passing |
| Android `app`: diagnostics screen (outputs, permissions, audio props) | 4 unit tests passing, lint 0 issues |
| Debug APK build | `./scripts/build-android.sh` → `dist/TheatreSync-debug.apk` (minSdk 29, targetSdk 35) |
| CI: Android (tests, lint, APK artifact) + Contracts/iOS static + docs links | `.github/workflows/` — first run happens on the PR |
| iOS Swift Playgrounds package: diagnostics view, shared models, clock sync | Static checks pass (**not compiled**) |

## Not yet verified

| Item | How it gets verified |
|---|---|
| iOS package compiles & runs | HW-02 on iPad |
| App sees WH-1000XM4 as A2DP output | HW-01 on Motorola |
| GitHub Actions workflows green | First PR run |

## Next milestones (proposed)

1. **M1 — Audio capture:** foreground service + MediaProjection consent + `AudioPlaybackCapture` → `AudioTrack` passthrough with adjustable delay. Physical test HW-03.
2. **M2 — Latency calibration:** OS latency estimates + manual lip-sync slider, feeding `LatencyAligner`.
3. **M3 — LAN sync:** host/listener discovery (NSD/Bonjour), clock exchange over UDP/WebSocket, iPad as remote.
4. **M4 — Cast:** Google Cast sender integration, discovery and session control.

## Open questions for the owner

* Sections 1–16 of the product brief aren't in the repo yet — please add them (e.g. `docs/PRODUCT-BRIEF.md`) so the architecture can be checked against them.
* Exact Motorola model and Android version (HW-01 will report this).
* Which video app(s) will be the source? (Determines whether AudioPlaybackCapture is allowed.)
