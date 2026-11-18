# TheatreSync

Bluetooth + Chromecast audio/video sync for a home theatre room.

TheatreSync keeps picture (projector via Chromecast) and sound (Bluetooth
headphones such as the Sony WH-1000XM4, or other outputs) lined up, by
measuring each output's latency and delaying the faster paths to match.

> **Status:** Milestone 0 — foundation. See [PROJECT-STATUS.md](PROJECT-STATUS.md).

## Repository layout

| Path | What it is |
|---|---|
| [`android/`](android) | Kotlin/Gradle Android app (`app`) + pure-Kotlin sync engine (`sync-core`) |
| [`ios/TheatreSync.swiftpm`](ios/TheatreSync.swiftpm) | SwiftUI app package for **Swift Playgrounds on iPad** |
| [`contracts/`](contracts) | Shared JSON protocol schema + fixtures — the single source of truth both apps follow |
| [`scripts/`](scripts) | Cloud setup, build and test scripts |
| [`docs/`](docs) | Architecture, workflow, iPad and physical-testing guides |

## Quick start (cloud — no local PC needed)

```bash
./scripts/run-tests.sh        # contracts + iOS static checks + Android tests & lint
./scripts/build-android.sh    # -> dist/TheatreSync-debug.apk
```

Every pull request runs these in GitHub Actions and attaches the APK as a
downloadable artifact. See [docs/CLOUD-WORKFLOW.md](docs/CLOUD-WORKFLOW.md).

## What "verified" means here

* **Cloud-verified:** compiles, unit tests, lint, schema validation.
* **iOS:** statically checked only on Linux — compiled when you open it in Swift Playgrounds.
* **Hardware-verified:** only after a physical test in [docs/PHYSICAL-TESTING.md](docs/PHYSICAL-TESTING.md) is reported back.

Cloud builds never prove Bluetooth, audio capture, or Cast behaviour.
