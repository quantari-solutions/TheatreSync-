# Architecture (draft, Milestone 0)

> Built from the README's goal ("Bluetooth chromecast sync for theatre room") and
> the hardware named in the cloud-first brief. Sections 1–16 of the original
> product brief have not been added to the repo yet; when they are, this document
> is revised to match them.

## Problem

In a projector room, picture usually travels **phone → Chromecast → projector /
AV receiver** while sound may go **phone → Bluetooth headphones**. Each path has
its own delay (Bluetooth A2DP often 150–300 ms; Cast varies). Without
correction, lips and voices drift apart.

## Approach

1. **Measure** each output's latency (OS-reported estimate + user lip-sync trim).
2. **Plan** extra delay for the faster outputs ([`LatencyAligner`](../android/sync-core/src/main/kotlin/com/theatresync/core/LatencyAligner.kt)).
   Outputs we cannot delay (e.g. a Cast receiver) are flagged with the residual error.
3. **Share a clock** between devices on the LAN using an NTP-style exchange
   ([`ClockSync`](../android/sync-core/src/main/kotlin/com/theatresync/core/ClockSync.kt)), so a
   second device (e.g. iPad as remote or extra listener) knows the host's media position.
4. **Capture & re-time audio** on Android using `AudioPlaybackCapture` (Android 10+)
   and play it via `AudioTrack` with the planned delay. *(Later milestone.)*

## Components

```
            LAN (protocol v1, JSON — contracts/)
  ┌──────────────────────┐         ┌────────────────────────┐
  │ Android host (Kotlin)│◄───────►│ iPad / iPhone (SwiftUI) │
  │  app  ── UI, capture │         │  remote / listener      │
  │  sync-core ── pure   │         │  Shared/ ── same models │
  │    logic, unit-tested│         │    & clock algorithm    │
  └─────────┬────────────┘         └────────────────────────┘
            │ Cast SDK (later)         │ AVAudioSession
            ▼                          ▼
      Chromecast → projector      AirPlay/BT outputs
            │ A2DP
            ▼
      Sony WH-1000XM4
```

* `android/sync-core` has **no Android dependencies**, so all timing maths runs in
  plain JVM unit tests in the cloud.
* `contracts/` is authoritative. Kotlin tests round-trip every fixture; the iOS
  static check compares Swift enums to the schema. A change to the protocol must
  update the schema + fixtures first.

## Known platform constraints

* `AudioPlaybackCapture` only captures apps that allow it (`allowAudioPlaybackCapture`);
  many streaming apps with DRM opt out. Needs physical confirmation per app.
* iOS cannot capture other apps' audio; the iOS app's role is remote/listener.
* Cast receivers cannot be delayed by us; we can only delay local outputs.
* Native iOS builds, signing and App Store need Xcode on a Mac. Swift Playgrounds
  on iPad can build and run the `.swiftpm` app for personal testing.
