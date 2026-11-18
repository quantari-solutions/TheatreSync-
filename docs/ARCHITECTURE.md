# Architecture

## The room

```
 Android phone (host) ──Wi-Fi──► Chromecast ──HDMI──► AV receiver ──► Projector
   │  serves the movie file over HTTP      (room speakers muted by the app)
   │  plays the sound in step with the Chromecast
   ├── Bluetooth ──► your headphones (WH-1000XM4)
   │
   └──Wi-Fi── friend's iPhone / your iPad (listener)
                 fetches the same file, plays only the sound, in step
                 └── Bluetooth ──► their headphones
```

## Why this design

* **No audio streaming codec.** Every device plays the *same file* from the host, and
  only the timing is shared. That's simpler and more reliable than streaming live audio,
  and it works for any number of listeners.
* **The Chromecast is the master clock.** We can't delay a Chromecast, so everything else
  follows it. The host reads the Chromecast's position (Cast SDK) and publishes it as
  `playback_state`. Listeners convert it with an NTP-style clock offset.
* **Gentle corrections.** Drift is removed by playing up to 5 % faster or slower (pitch
  preserved), so there are no audible jumps. Large drift (start, seek, stall) is fixed by seeking.
* **Lip-sync buttons.** Bluetooth delay, and the receiver/projector picture delay, differ
  per device. Each person taps "Sound is early/late"; the value is remembered.
* **Default Media Receiver.** No Google Cast developer registration or receiver web app is
  needed. The Chromecast loads `http://<phone>:<port>/media` directly.

## Code map

| Part | Where | Tested how |
|---|---|---|
| Protocol schema + fixtures | `contracts/` | Schema validator; Kotlin + Swift round-trip tests |
| HTTP server, clock sync, drift correction | `android/sync-core` (plain Kotlin) | 37 JVM tests incl. closed-loop sim and HTTP end-to-end |
| Android host app | `android/app` | Unit tests, lint, emulator tests (UI, on-device server, real audio locking onto a simulated TV clock) |
| iOS listener app | `ios/TheatreSync.swiftpm` | Shared code compiled + tested with Swift on Linux; UI statically checked. App compile only in Swift Playgrounds |

## Now casting

`TvWatchers` opens one Cast v2 connection (TLS, port 8009) per Chromecast found by
MediaRouter and asks the receiver what it's running (`RECEIVER_STATUS`), then joins the
running app's media channel for `MEDIA_STATUS` (title, artwork, position). This is the
same public protocol that Google Home and open-source clients such as
[pychromecast](https://github.com/home-assistant-libs/pychromecast) use, so it works for
casts started from any device. Code: [`CastV2.kt`](../android/sync-core/src/main/kotlin/com/theatresync/core/CastV2.kt),
[`CastWatcher.kt`](../android/sync-core/src/main/kotlin/com/theatresync/core/CastWatcher.kt).

## Limits

* Streaming apps (Netflix etc.) can't be handled in software: see [STREAMING-APPS.md](STREAMING-APPS.md).
* Older Chromecasts play MP4/H.264 + AAC (and WebM) reliably; MKV/HEVC/DTS may fail.
* The iPhone/iPad can't send to the Chromecast yet (the Google Cast iOS library needs Xcode),
  so the Android phone is always the one that starts the movie.
* Cast position is an estimate (±tens of ms). The lip-sync buttons cover what's left.
