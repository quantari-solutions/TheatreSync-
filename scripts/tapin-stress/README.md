# TapIn stress test

`scripts/tapin-stress/run.sh` serves the real listening page (taken from `ListenPage.kt`) from a
simulated TheatreSync host and drives it in Chromium through 12 situations: start, steady play,
TV pause/resume, jumps (+2 min, −1 min, +0.8 s), the host's clock resetting, slow Wi-Fi (600 kbps)
with a jump, a dropped connection, the page hidden and shown again. It measures the page's real
drift against the simulated TV (after the phone's reported output delay) and passes a step when
the last 3 s stay within ±80 ms.

To test with the app's own sound-only remux of a movie, make it first:
`TS_EXTRA_MP4=/path/movie.mp4 ./gradlew :sync-core:test` → `android/sync-core/build/tmp/audio_only_extra.mp4`,
then `scripts/tapin-stress/run.sh android/sync-core/build/tmp/audio_only_extra.mp4`.

A browser on a desktop is not a phone with Bluetooth headphones: real-world sync still needs HW-18.
