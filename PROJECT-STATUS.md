# Project status

_Last updated: 2026-11-20. 0.5.5 (owner releases when ready): host controls TapIn guests; live join/leave; sync for every device_

## 0.5.5: host controls TapIn guests

| Request | Done | Verified |
|---|---|---|
| Host controls guests using the web page | Audio sheet → **Listening now**: each guest's name, sync and status, with **Sound on** (mute), **volume** (− / +) and **Remove**. Changes reach the guest within ~1 s (their page reports every second and gets the controls back). iPhones ignore page volume (mute works) | `Guests` + route unit tests; browser test: mute 0.7 s, unmute/volume ≤ 1 s, remove 0.1 s |
| Leaving/joining shows immediately | Guest appears on the host the moment they tap **Start listening**; **Stop listening** or closing the page sends `/leave` (sendBeacon) → gone at once. A page that disappears without it is dropped after 4 s; a locked screen (still listening) after 10 min | Browser test: join 4 ms, Stop 46 ms, page closed 6 ms |
| Sound starts right away | Unchanged fast start (sound-only stream from 0.5.4); Start → sound in ~0.1 s in the browser test | Browser test |
| Sync for all outputs on the phone | Your playing headphones show "In step · +12 ms" in Audio sources; each guest shows theirs | Screenshot `guests1`; needs HW-19 |
| Guest page shows only their own device | "Sync: +12 ms on this phone"; optional name field (default "Android phone · Chrome" etc.) | Browser test |
| Smooth switching between headphones | Guest page: switching headphones re-measures and allows one correcting jump straight away. Host phone: unchanged (settle + relock on route change) | Browser test (simulated switch: −19 ms after) |

## 0.5.4: TapIn sound always in step

| Problem | Done | Verified |
|---|---|---|
| Guests' phones downloaded the whole movie to play its sound (slow start, slow jumps, gaps on busy Wi-Fi) | `Mp4AudioOnly` (sync-core): a sound-only MP4 built on the fly from the original (rewritten index, sound chunks streamed untouched). Swapped in only before any guest has started, so nobody's file changes under them | Unit tests (synthetic + real ffmpeg files); ffprobe: AAC 5.1, decoded sound identical to the original |
| Listening page could jump over and over, or stay silent after a pause/jump | Same rules as the headphone player: settle 1 s after any start/jump, at most one jump per 5 s (speed nudges in between), learned start lead, one calibration jump; waits while loading; restarts a stalled stream after 6 s; reconnects after errors; resyncs when the page comes back; asks for another tap if the browser blocks sound; counts the phone's own output delay where the browser reports it | `scripts/tapin-stress/run.sh` in Chromium: 12 situations (pause, jumps, slow Wi-Fi, dropped connection, host clock reset, hidden page) all within ±25 ms |
| Host app restarted mid-movie → page kept a wrong clock for ~30 s | Clock readings that jump by more than a second start a fresh set | Stress test |
| Dolby/DTS movies: guests silent with no explanation | Prefers an AAC/MP3/Opus/FLAC sound track when the movie has one; otherwise the page says the sound format can't play in a browser (no endless retries) and the Audio sheet tells the host | Unit test + Chromium with an AC-3 file |

Limits: browsers can't play Dolby/DTS; a guest's Bluetooth delay is only partly known to the browser, so **Sound is early/late** stays for fine-tuning. Real phones + headphones: HW-18.

## 0.5.3: subtitles and ad skipper

| Request | Done | Verified |
|---|---|---|
| Load .srt subtitles | Subtitles row under the movie: **Load file** (.srt/.vtt) → converted to WebVTT, served at `/subs.vtt` (CORS), added as a Cast text track; **CC** switch on the movie page | Parser/route unit tests; needs HW-17 |
| Auto-generated subtitles for own videos, any language | **Generate**: decodes the movie's sound, cuts it at pauses (energy VAD, 1.5–12 s pieces), runs Android's **on-device** speech recognizer on each piece (Android 13+ audio-from-file), writes timed cues; **translation** on the phone with ML Kit (language downloaded once). Saved per movie. Progress + time left; Cancel | Chunker/resampler/cue unit tests; recognition speed and accuracy need HW-17 (depends on the phone's offline speech models) |
| Ad blocker like ReVanced | ReVanced patches the YouTube app *on the phone*; ads on the TV come from the TV's own YouTube app, which can't be patched. Built instead what open-source **iSponsorBlockTV** does over YouTube's remote protocol: press **Skip ad** as soon as allowed, **mute** unskippable ads, skip **SponsorBlock** segments (sponsor, self-promo, interaction). Command names checked against the pyytlounge source. Experimental → Ad skipper (on by default) | Event/SponsorBlock parser tests; needs HW-17 |
| Two Bluetooth headsets on one phone | Not possible for one app: Android keeps two headsets *connected* but streams media to one *active* device (A2DP); there is no API to send audio to both. Exceptions are LE Audio/Auracast (Share audio page) and some brands' own "dual audio" in their system software | — |
| APK size | ML Kit's translator adds ~16 MB native code per chip type → one APK per type (arm64 for phones: 24 MB; x86_64 for the CI emulator) | Built |

## 0.5.2: owner feedback

| Request | Done | Verified |
|---|---|---|
| Signature intro before YouTube and mirroring | `CastIntro` (sync-core) plays the clip through the Default Media Receiver and waits for it to finish, then YouTube starts / Android's cast screen opens. For YouTube cast *from TheatreSync* and Mirror from TheatreSync. Casting from other apps (YouTube/ReVanced cast button, laptop Chrome) can't be intercepted. Smart TVs (DIAL) have no player for it | Load/status unit tests; needs HW-16 |
| Opening animation on app start | Same clip full screen on a fresh start (new process) only; coming back from another app resumes where you were. Tap to skip | Compiles; skipped under instrumentation |
| Up next + swipe | Reads the TV's YouTube queue from Lounge events (playlist/Mix + autoplay pick), titles via YouTube oEmbed; row of thumbnails, tap to play; swipe the big picture left/right for next/previous. No animations | Parser unit tests; needs HW-16 |
| No animations | Page changes are instant | — |
| The Frame not under Choose your TV | Listed there as "Smart TV · YouTube only"; tapping explains and offers YouTube | Screenshot |
| Headphone battery | Shown in Audio sources where the phone shares it (Android has no public API; read via the hidden getBatteryLevel, else omitted) | needs HW-16 |
| Share app | Settings → Share TheatreSync: sends the APK (Android); text tells iPhone users to join with TapIn (iPhones can't install outside the App Store) | needs HW-16 |
| Same sound on 2+ headphones (music/calls) | Experimental → **Share audio** switch; when on, a menu page checks LE Audio broadcast (Auracast) support and opens Android's audio sharing. Needs LE Audio in phone *and* headphones; otherwise Android allows one headset. Movies: TapIn | needs HW-16 |
| Fix found while doing this | An unchecked text replacement in 0.5.1 had silently skipped wiring "dismiss message" on the remote page; all edits now use checked replacements and a wiring audit | — |

## 0.5.1: owner feedback on 0.5.0

| Report | Cause / answer | Done | Verified |
|---|---|---|---|
| Want checkboxes, one "Audio" place | — | 🎧 **Audio** (top right of the movie and Now casting pages, and the movie page's Audio row) opens **Audio sources**: checkboxes for Room speakers (TV), every connected headset, the phone speaker; Guests (TapIn) with **Share by NFC** / **Show QR code** buttons | Compiles; screenshots of pages |
| Room speakers silent; mute toggle did nothing | Room sound was off by default, and surround movies sent the TV picture only | The toggles are gone. **Room speakers** checkbox: on = TV sound (surround movies restart at the same spot with converted sound); off = TV silent. Remembered | needs HW-15 |
| Two headsets at once (Sony took over from MOMENTUM 4) | Android plays media on **one** Bluetooth headset per phone; connecting a second makes it the active one. No app can change this | Audio sources lists every connected headset (ticking an idle one switches to it) and explains; second pair → TapIn on another phone | — |
| Signature animation didn't show | It was off by default (Settings → Experimental) | **On by default** now; not played on a mid-movie restart | needs HW-15 |
| Next (remote) did nothing | YouTube ignores Next with nothing queued (single video). No error, so no crash report | After Next/Previous the app checks the TV changed video; if not it says why ("works inside a playlist or Mix") and logs it. Messages now show on the remote and Now casting pages | needs HW-15 |
| NFC chooser ("TheatreSync or NFC Tools?") | Another installed NFC app registers the same NFC tag ID | NFC sharing is **only active while "Share by NFC" is open**, and TheatreSync is then the preferred NFC service (Android's official way to skip the chooser). QR only on request | needs HW-15 |
| Remote while casting | — | 🎛 button top right of the movie and Now casting pages | Screenshot |
| Pull down to refresh | — | Pull down on any page: re-scans Chromecasts (active scan 10 s), smart TVs (DIAL) and headphones | Compiles |
| Glow around what's playing | — | A soft gold glint circles the Now casting / Movie playing card every 5 s while playing | Screenshot (still frame) |
| Ad blocker | YouTube's ads are played by the TV's own YouTube app; a phone app can't remove them (YouTube Premium is the only way) | Not built | — |
| Auto captions for any video | YouTube: Captions button already offers YouTube's own (incl. auto-generated) tracks. Own movies: needs on-device speech-to-text + translation; large feature | Not built yet; first step offered: load .srt subtitle files and show them on the TV | — |

## 0.5.0: TapIn, picture in picture, crash reports in one place

| Request | Done | Verified |
|---|---|---|
| NFC "third-party connect" for guests without the app (iPhone + Android) | **TapIn**: while a movie plays, the phone serves a listening web page (`/listen`) that plays the movie's sound (`/audio`, always with sound even when the TV gets picture only) in sync using `/clock` + `/state`. Guests open it by **tapping their phone to the host** (Host Card Emulation as an NFC Forum Type 4 tag holding the URL) or by **scanning the QR code**. Guests count as friends listening | Type 4 tag APDU sequence unit-tested; server routes tested; listening page run in Chromium against a stand-in host: **in step within 18 ms**. NFC tap on a real iPhone/Android needs HW-14 |
| Moving a guest's paired headphones to the host phone | Not possible: Bluetooth headphones only connect to the phone they're paired with. TapIn gives the same result (guest's own phone + headphones) | — |
| Headphones with NFC | Android itself pairs NFC headphones on touch; TheatreSync then offers to switch to them (new-headphones prompt) | needs HW-14 |
| Picture in picture | ⧉ button on the movie player and Now casting, and **Pop out** in the casting/movie notifications: floating window with the TV's thumbnail, title, state and a play/pause button | needs HW-14 |
| Signature Intro (Settings → **Experimental**) | 4.3 s before each movie: the Quantari Solutions logo with its gold sheen (from quantari-solutions.vercel.app), then “Presented with TheatreSync · a Quantari Solutions module”, on the app's dark theme (owner: no cube). Queued ahead of the movie in one Cast queue; headphones stay silent until the film is on screen. Off by default | Video rendered and checked frame by frame; route test; needs HW-14 |
| Crash reports in one place | Settings → Crash reports now holds automatic upload (setup, connect, test, status) and the timeline; separate row removed | Screenshot, emulator test updated |

## 0.4.9: owner feedback (Red Eye sound worked in the headphones; conversion too slow)

**Release rule from the owner:** don't send an APK until the owner says to release.

| Request | Done | Verified |
|---|---|---|
| Conversion very slow | **Picture-only MP4, instant:** for surround-sound MP4s the TV gets the file minus its sound tracks; only the index (moov) is rewritten in memory, chunk offsets shifted for fast-start files, media bytes streamed untouched. Headphones play the original sound. "Room speakers too" switch keeps the old sound conversion | Unit tests (both layouts, range reads); needs HW-13 |
| New headphones mid-movie | AudioDeviceCallback: prompt "Use MOMENTUM 4?" (or switch automatically, setting); removed headphones fall back at once; lists refresh live | needs HW-13 |
| YouTube sound to headphones | Not possible (the TV's YouTube app plays the sound). Said plainly in Headphone settings | — |
| Remote from the notification | "Remote" action in the casting and movie notifications opens the remote page | needs HW-13 |
| Bigger headphone buttons, instant switch | Rows ≥ 64–80 dp; tapping switches the player's output immediately | Screenshot |

## 0.4.8: owner feedback on 0.4.7

| Report | Fix | Verified |
|---|---|---|
| Samsung "The Frame" not listed | **DIAL** discovery (SSDP) of non-Chromecast smart TVs; YouTube started via DIAL, its screen ID fed to the same Lounge remote. Listed as "Smart TV · YouTube only" (movies still need a Chromecast) | Parser unit tests; needs HW-12 |
| Remote ±10/30 s did nothing | YouTube ignores media-channel seeks from other senders: play/pause/seek now go through YouTube's Lounge remote (`play`, `pause`, `seekTo`) | needs HW-12 |
| Couldn't get back to the home screen | The home screen was replaced by the movie player whenever a movie existed (even finished). Movie player is now its own page with ←; home shows a "Playing on …" card | Screenshot |
| TV chips on the remote; "Living Room" misaligned | Chips removed (remote uses the connected/playing TV). All button labels single-line with "…"; radio marks drawn at fixed size (glyphs differed in width); long-name screenshot test | Screenshots |
| Headphone options | ⚙ on Headphones page, ☰ → Headphones, and a Headphones button in the movie player and Now casting open **Headphone settings**: device, sync tool (live drift, ±10/±50 ms, Reset sync), equalizer (presets, bass boost, 5 bands; headphone sound only) | Screenshot; EQ needs HW-12 |
| Real-time sync | Already automatic (drift correction every 200 ms); the sync tool shows it live. Microphone-based calibration not built | — |

## 0.4.7: owner's test night (crash reports now arrive on GitHub)

| Report | Cause found | Fix | Verified |
|---|---|---|---|
| Headphones silent 9 times in 10 (test beep fine) | Issue #17: headphone player stuck "buffering". Bluetooth takes ~0.5 s to start; the sync code measured that as drift and seeked, which restarted Bluetooth, forever | 1 s settle time after every start/seek/output change; learned start lead up to 2 s; no second seek within 6 s (smooth catch-up instead) | Unit tests + emulator; needs HW-11 |
| Switching phone speaker ↔ headset went silent | Same loop, triggered by the output change | Output change gets a 2 s settle | needs HW-11 |
| Converting for 30 min, then nothing | Issue #20: conversion finished while the app was in the background; Android refused to start the movie service | Movie service starts when Start is tapped, and is idempotent | Crash report evidence; needs HW-11 |
| Red Eye (720p H.264, AAC 5.1) "can't play" though shown as fine | Older Chromecasts only decode stereo AAC; channel count wasn't checked | Verdict checks channels; **sound-only conversion** copies the picture untouched and downmixes 5.1/7.1 to stereo (minutes, not tens of minutes); never re-encodes the picture when the quality is at/above the movie's own | Unit tests; speed needs HW-11 |
| Conversion time unknown | — | "About N min left" | Screenshot |
| Remove the chosen movie | — | ✕ next to Change | Screenshot |
| Drag to any point | — | Draggable progress bar (movies and YouTube) | Screenshot |
| YouTube next / previous, captions | — | Lounge remote commands `next`, `previous`, `setSubtitlesTrack`; long-press captions for language | Unverified on a TV (undocumented YouTube API) |
| Full Chromecast remote | — | ☰ → Chromecast remote: play/pause, ±10/30 s, next/previous, volume ±, mute, captions, stop | Screenshot |
| ReVanced instead of YouTube | — | "Open YouTube" opens ReVanced first; Share → TheatreSync casts the shared video | needs HW-11 |
| "Martin Garrix" card with nothing playing | Cards were shown for any app open on any TV | Hidden when nothing is loaded, idle, or "playing" with a frozen clock for 45 s | needs HW-11 |
| Multiple Bluetooth headphones on one phone | Android plays media to **one** Bluetooth headset at a time (no app can change this) | Each extra listener joins with their own phone (iPhone/iPad app) | — |
| YouTube quality | The TV's YouTube app picks quality itself (older Chromecast: up to 1080p); the remote API has no quality control | — | — |
| Samsung "The Frame" | It's not a Chromecast (YouTube via DIAL, DLNA) | Planned: YouTube to DIAL TVs | — |

## 0.4.6: picture missing for phone movies, mute had no effect

Owner report: sound in the headphones but no picture on the TV for a movie from the phone; YouTube and mirroring fine;
the mute toggle did nothing; no controls in YouTube mode; wanted a quality choice and a better TV icon.

| Issue | Fix | Verified |
|---|---|---|
| Sound but no picture | Likely cause: the older Chromecast (max 1080p, H.264/VP8 only) can't decode 4K or H.265/HEVC phone videos, while the Default Media Receiver still plays the audio. The app now reads the movie's format and tells you whether your TV can show it | Unit tests (CastCompat); needs HW-10 |
| Quality choice | **Original / 1080p / 720p** chips. 1080p/720p convert once on the phone (Media3 Transformer → H.264 + AAC, hardware encoder) with a progress bar and Cancel; the result is kept so the next play is instant. Default picks what the TV can show | Builds and unit tests (size maths); conversion speed and output need HW-10 |
| Loads slowly | Smaller converted files stream far faster over Wi-Fi than 40–60 Mbit/s originals. (Your 5G only matters for YouTube; movies from the phone go over the home Wi-Fi) | needs HW-10 |
| Mute room speakers did nothing | Now also sets the movie stream volume to 0 and the TV's volume mute, then checks 2 s later and says plainly whether the TV accepted it | needs HW-10 |
| No controls in YouTube mode | Now casting page: **TV volume** slider + Mute/Unmute (sent straight to the Chromecast; works for YouTube, mirroring and movies) | Protocol unit tests; needs HW-10 |
| TV emoji icon | Material cast / speaker / film icons in gold tiles | Screenshot tests |
| Automatic crash reports | Set up entirely on the phone: one-tap prefilled GitHub App, paste Client ID in the app, approve, automatic **test report** proves it ("Uploaded #N"); refusals explained. Emulator test against a fake GitHub | Emulator test; real upload needs the owner's one-time setup |
| Sound test placement | Already in the Headphones page ("Test headphone sound") | Screenshot tests |

## 0.4.5: owner's media-room test (headphones work; TV keeps buffering)

Evidence from screenshots: "Sound on MOMENTUM 4", sync −359 ms, status flipping to "Waiting for picture" every 3–4 s;
the phone's network meter showed ~0.5–1 MB/s while casting a phone-recorded video (~15–20 Mbit/s needed).

| Issue | Fix |
|---|---|
| Chromecast stalls every few seconds | HTTP keep-alive (one connection for all range requests instead of a new TCP connection per chunk), 1 MB socket send buffer, 256 KB copy buffers (tested: 3 ranges on one connection) |
| Sound ~350 ms behind after each restart | Learned start lead: headphone sound starts slightly ahead and adapts after each restart |
| Confusing "Waiting for picture" | "TV is loading…" while the Chromecast buffers |
| No way to tell if the Wi-Fi is the bottleneck | Speed check: measures Mbit/s to the TV vs the video's bitrate; after 3 stalls in 60 s it says so plainly and records it |
| TV list said "Connect" while YouTube was playing | Each TV shows what it's playing ("Playing YouTube" · "Use for movie") |
| Can't tell if headphones get TheatreSync's sound | **Headphone sound test**: plays beeps on the movie audio path, shows the device Android routed it to, asks "Did you hear it?", records failures |

## 0.4.4: owner feedback

| Request | Done |
|---|---|
| No crash card; reports should just be on GitHub | Card removed. One-time **Connect GitHub** (device flow, no secret in the app) uploads crashes and handled problems as issues automatically. **Needs the owner's GitHub App Client ID**: see [docs/CRASH-REPORTING.md](docs/CRASH-REPORTING.md) |
| Coming back from another app shouldn't restart | The main cause was the onStop crash (fixed in 0.4.2). Page is kept across Android reclaiming the app (emulator test); the chosen movie is restored; Now casting waits for the TV instead of bouncing home |
| Pick headphones without opening Settings | **Choose or connect headphones** opens Android's Output Switcher over the app (connects paired devices) |
| Two devices connected: choose one | **Play TheatreSync sound on** list (ExoPlayer preferred audio device) |

## 0.4.3: owner's media-room feedback

| Report | Fix |
|---|---|
| YouTube: after Cast, the page didn't change (button came back as if nothing happened) | Button shows "Starting on TV…"; on success the Now casting player opens at once with the video thumbnail (optimistic entry until the TV's status arrives); the TV is polled immediately |
| Movie sound still in the room speakers | Also mute the movie's own stream on the receiver (`setStreamMute`), re-applied when playback starts; device mute is often ignored with HDMI/CEC fixed volume |
| Nothing in the headphones | Watchdog: if the TV plays for 5 s without headphone sound, say so and record the player state/error (crash timeline + ⓘ details). Cause not yet confirmed on the device |
| YouTube sound in the room speakers | Expected: YouTube streams straight to the Chromecast; no app can route it to the phone's headphones |

## 0.4.2: root cause from the owner's crash report

The on-phone crash reporter captured it (motorola edge 40, Android 15):
`NetworkOnMainThreadException` in `MainActivity.onStop` → `TvWatchers.stopAll` → `CastWatcher.close` →
closing the TLS socket to a Chromecast sends close_notify, which is network I/O on the main thread.
`onStop` runs whenever the app goes to the background, e.g. opening Files to choose a movie, so this was the
"crashes when I pick a movie" bug. It was never reproduced on the emulator because no Chromecast connection exists there.

Fixes: the socket is closed on a background thread (regression test asserts close never runs on the caller's thread);
every TV/network/file action goes through a guard that records the problem and keeps the app running;
crash timeline under ☰ → Settings → Crash reports (Send to GitHub opens a pre-filled issue the owner submits,
or Share); cast icon in the status bar with a Stop casting action while connected.
Crash tests: monkey (3000 random events on the optimised build), 15× background/foreground, 5× recreate.


## 0.4.0 — field-test fixes (from the owner's media-room test)

| Report | Cause | Fix |
|---|---|---|
| App closes after choosing a movie | (1) MovieSession's `init` ran before `routerCallback` was initialised: a crash whenever a Cast session already existed at start-up, e.g. after Android restarted the app behind the file picker. (2) Any unexpected exception on a movie-server thread killed the whole app, so the TV sat on a spinner. | `init` moved last and deferred, with a regression test; server threads catch everything (tested with a failing file provider); sync engine fully kept by R8; on-device **crash reporter** with "Share crash report" |
| No way to stop the cast | Missing feature | **Stop casting** on the Now casting card and page (receiver STOP, tested against a fake Chromecast) |
| Movie starts but nothing appears on the projector (phone joined Wi-Fi just before Start) | Not yet confirmed. Likely: the phone gave the TV an address on the wrong network (phones can have several, e.g. Wi-Fi Direct) | 0.4.1: pick the phone address on the Chromecast's subnet (tested); 20-second check that says plainly if the TV never reached the phone; ⓘ details show the URL, both IPs and how many times the TV fetched the movie |
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
