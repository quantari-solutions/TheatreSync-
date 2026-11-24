# Project status

_Last updated: 2026-11-22. Android 0.7.0: TapIn fixed and on the movie screen, TapIn for YouTube (any phone's cast). iOS 0.7.0: full TheatreSync part 1_

## Android 0.7.5: "remote cast isn't working… it says connecting… doesn't cast"
| Finding | Change | Checked |
|---|---|---|
| No crash reports: YouTube accepted the request, but a Chromecast only listens over the internet while its YouTube app is open; idle (backdrop/screensaver) it can't be reached from outside | YouTube's bind reply says whether a TV is connected ("loungeStatus" → LOUNGE_SCREEN); when it isn't, the app says so at once instead of "connecting". Real fix = home hub (Google TV Chromecast) or router VPN | `YouTubeTest.knowsWhetherTheTvIsListening`; needs HW |
| Screensaver ~20 fps with a pause at each loop; "keep everything 60fps"; random sparks | Owner chose free (no $5 receiver). New picture: 960×540, exactly 60 fps (17/17/16 ms), 24 s seamless loop, 110 random-looking embers, ~half the decode work per frame (6.1 MB). Same file on iOS | Frame timing verified from the file; smoothness needs the owner's TV |
| The Frame not on Android; friend's iPhone casts to it | iPhone uses AirPlay (Apple only); Smart View needs Miracast (not on Motorola); Tap View is Samsung phones only. TheatreSync: Wi-Fi address search also tries Samsung's YouTube launcher (port 8080) when port 8001 is closed; Remote cast page has an "Open SmartThings" button (switch The Frame on / open YouTube from anywhere, then cast YouTube from TheatreSync) | Build; needs HW |

## Android 0.7.4 / iOS 0.8.3: remote cast feedback (owner tested from work)
| Ask | Done | Checked |
|---|---|---|
| "I have no way of figuring out [if it's casting]" | YouTube's remote-control events are read every few seconds; the TV itself reports playing/paused/loading and its position (`LoungeState`). Card + page say "Playing at home ✓ (confirmed by YouTube on the TV)" or "Not confirmed yet: is it on, with YouTube open?" | `LoungeStateTest`; needs HW |
| Golden glow should travel round the card while playing | It existed but used Android's animation system, frozen by "Remove animations"; now frame-clock driven (Android), TimelineView (iOS `OrbitCard`) | Screenshots; needs phone check |
| No "You're away from home" card while remote casting; stop + cast another | Card hidden during a remote cast; Now casting card and page show play/pause, skip, stop and "Cast another video" for remote/smart-TV casts | `remoteCast2_playing` screenshot |
| Remote cast page with YouTube, phone media and mirroring | New Remote cast page: YouTube (ready); a movie from this phone and Mirror screen listed as "Needs the home hub (coming)", with each TV's kind (Google TV can be the hub; classic Chromecast needs the router's VPN) | `remoteCast1` screenshot |

## Android 0.7.3 / iOS 0.8.2: "I'm not able to see [remote cast]… make sure there is a remote cast toggle in experimental and it's enabled"
| Problem | Fix | Checked |
|---|---|---|
| Media Room only became "Away" after a YouTube cast on 0.7.2 | Android also uses the YouTube IDs older versions saved per TV (`ytScreen_<id>` + `tvAddr_<id>` name), so TVs cast to before appear right away | Build + tests; needs the owner's check from outside |
| Hard to find | Home shows "You're away from home → Cast YouTube from here" when no Chromecast is on the Wi-Fi but TVs are remembered; the YouTube page names the single TV and says it's over the internet | Screenshots re-recorded |
| Toggle | Experimental → Remote cast (on by default), Android and iOS | Screenshot; parity check |

## Android 0.7.2 / iOS 0.8.1: "cast something at my house while… at work"
| Ask | Done | Checked |
|---|---|---|
| Cast to the home TV from anywhere | YouTube only (option 1): each TV's YouTube screen ID is remembered after a cast at home; away from that Wi-Fi the TV appears as "Away · YouTube over the internet" and YouTube's Lounge service (internet, not Wi-Fi) plays the link and gives play/pause/skip/volume. Movies/title card/power from away need a home hub (option 2, later; a Chromecast with Google TV could be the hub) | Builds + tests; needs an away test (TV on; a Chromecast may need YouTube open) |
| "App support for both… automatically figure out which version" | Each Chromecast is checked once: Google's TV-remote service (ports 6466/6467) or a "Google TV" model name means Chromecast with Google TV (runs Android: can be the home hub, option 2); otherwise classic Chromecast (YouTube from away). Shown under the TV's name on Android and iOS | `CastModelTest`; needs HW check of the label |
| Unlock a classic Chromecast / online VM | Not possible: no public unlock after the 2013 model; a cloud VM is outside the home network like the phone. No-extra-device options: Google TV Chromecast as hub, or the router's own VPN | — |

## Android 0.7.1: "on the Android also I'm not able to see the frame"
| Cause found | Fix | Checked |
|---|---|---|
| Samsung's Wi-Fi announcement was resolved several at a time (Android refuses overlapping resolves, so some were silently lost) | One at a time, in a queue | Build + unit tests |
| A TV found that way was dropped unless its YouTube answered DIAL at once (The Frame in Art mode often doesn't) | Kept when the TV's own "who are you" reply (port 8001 /api/v2/) says it's a Samsung TV | `SamsungTvTest` |
| "See the frame… in the sources list grouped with media room" | One TV list for Chromecasts and smart TVs (Samsung/LG/Sony via DIAL); tapping The Frame opens YouTube with it already chosen (Android and iOS) | Build |
| Both routes rely on the router passing multicast | New: every minute while the app is open, each Wi-Fi address is asked on port 8001; plus a Samsung TV address box in Settings (same as iOS) | Settings screenshot re-recorded; needs HW test |

## iOS 0.8.0: "like a carbon copy to the Android one on the iOS"
| Owner's ask (iPad, Swift Playgrounds) | Done | Checked |
|---|---|---|
| No back buttons / no settings / no menu bar | Android layout copied: brand bar + ☰ menu, Quick watch tiles, Your TVs, Movie night steps. Pages with back buttons: Now casting, Remote, YouTube, Settings, Listen along | iOS simulator CI screenshots of each page |
| Doesn't connect to The Frame (Samsung) | Found through Samsung's own Bonjour announcement (`_samsungmsf._tcp`, the one route iOS allows without Apple's multicast permission), or by address typed in Settings. YouTube cast via DIAL (port 8080) + YouTube Lounge, with play/pause/skip/volume | Shared parsing tested (swiftc); needs HW test |
| Everything else from Android | YouTube casting (Chromecast via mdx screen ID, Samsung via DIAL), Signature intro before YouTube, seek bar with "Loading…", TV sound in headphones for YouTube (WKWebView, same sync page as Android), TapIn for YouTube by QR code (served from the iPhone), projector/receiver power, screensaver | Needs HW test |
| Local Network asked on every launch | Swift Playgrounds re-asks each run; an installed app asks once (see "A real app, for free" in docs/IOS-SWIFT-PLAYGROUNDS.md) | — |
| "Connect to Media Room… I'm not able to go back to the main screen" | Listen along and Listening were full screens with the back button hidden; now normal pages with a back button (the sound keeps playing; a Listening row on home leads back) | `scripts/ios-parity-check.py` fails if any page but Home hides its back button |
| "More auditing… everything intact just the way I had planned" | New `scripts/ios-parity-check.py` (in run-tests.sh): every Android page must have an iOS page or a written reason, and 13 owner-asked features must stay present | Runs on every PR |
| Rest of Android | Movie night from Files (picture on the TV, sound in headphones in step, signature intro first), Headphones page (output, delay, sound test, Apple's output picker), Two headsets (AirPods Share Audio), Mirror (AirPlay to The Frame; laptop Chrome cast), Experimental page. Manual "sound early/late" buttons removed: automatic like Android | Needs HW test |
| GitHub projects looked at | OpenCastSwift (MIT, 2019): same Cast v2 method as ours, older. CastKit (2026, unproven). couch-remote/TVKit (MIT): Samsung key remote; Android doesn't have it, so not added (copy of Android) | — |
| Not possible on iOS | NFC TapIn, several Bluetooth headphones at once, self-installing updates | — |

## Also in 0.7.0 (owner, 2026-11-22 night)

| Request | Done | Verified |
|---|---|---|
| Seek bar jumps back to the old spot while the TV buffers | The bar stays where you let go with "Loading…" until the TV reaches it (or 15 s) | Compiles; needs the TV |
| Title card / screensaver at 60 fps; particles like gentle, very slow bonfire sparks | `loop.html`: warm embers rise slowly, sway, flicker and fade (cooling gold → orange); 12 s seamless loop; animated picture 1280×720 at 60 fps (8.7 MB), Android and iOS | Preview video sent; needs the TV |

## Android 0.7.0: TapIn

| Report | Cause | Fix | Verified |
|---|---|---|---|
| Can't see TapIn / the QR code sometimes | TapIn lived only in the Audio sheet and only during a movie from this phone; with YouTube or before a movie it just said "Start a movie" | The movie screen's guests card now shows the **QR code**, the address (typeable) and **Share by NFC** directly. The Audio sheet explains when TapIn is available. The QR dialog shows the address too | Screenshot `tapInCard` (QR renders) |
| NFC: "choose TheatreSync" pops up on my phone, nothing on the other | Two phones back to back both read each other; the host read the guest instead of being read | While sharing, the host stops reading (Android 15 `setDiscoveryTechnology`: reader off, card emulation kept), so the guest's phone reads the page | Compiles; needs two phones |
| YouTube cast from a friend's phone: no TapIn on Now casting | TapIn shared the phone's own movie sound; YouTube's sound comes from the TV | **TapIn for YouTube**: Now casting (YouTube, whoever cast it) shows a TapIn QR / NFC. The guest's browser (no app) plays the same video with YouTube's player, kept in step with the TV by this phone's `/yt/state` (same method as TV sound in headphones) | `YouTubeTapInTest` (page + state served); needs phones |
| "Keeps crashing" | No crash report reached GitHub; not reproduced | — (the crash reporter uploads the next one; if it happens again, please keep GitHub signed in under Settings → Crash reports) | — |


## iOS 0.7.0: "fix up the iOS app with the latest updates… setting → update for iOS as well"

| Request | Done | Verified |
|---|---|---|
| Same app on iPhone / iPad | Part 1: Chromecast discovery and direct Cast connection (`CastController`, `Shared/CastProtocol.swift`, mirror of `CastV2.kt`), Now casting with picture, play/pause, ±10 s, volume, mute, Stop; Projector & receiver Turn on / Turn off (`NetworkPower.swift`, mirror of Android); living title card + screensaver served from the device (`TitleCardServer`); opening animation (own clock, once per fresh start); listen along kept. Part 2 next: YouTube casting, TV sound in headphones | `ios-sim.sh` on GitHub's Mac: Xcode build, shared tests (incl. Cast protocol) compiled and run, launch + screenshots on iPhone and iPad simulators. Chromecast / projector need the real devices |
| Settings → Update on iOS | Apple doesn't allow apps to install updates. About shows "Update available" from `releases/ios-latest.json` with the new zip's link. Fully automatic updates need TestFlight (owner's Apple Developer account, US$99/yr) | — |


## 0.6.9: "I want the same animation… but no progress bar… an infinite loop… like a loading screen"

| Request | Done | Verified |
|---|---|---|
| Animated card, endless, no progress bar, no refresh; glow fading and moving around TheatreSync; shine across the Quantari swooshes; particles floating randomly; the sleek line | `docs/intro/loop.html`: the words stay still; the glow breathes (8 s) and drifts around the title (16 s); a shine sweeps the swooshes every 8 s, alternating direction; a light glides along the line; 80 particles wander on their own random paths and twinkle. Every motion repeats exactly every 16 s, so it loops with no visible join. Sent to the TV as an **animated picture** (1280×720 WebP, 15 fps, 3 MB, endless): pictures get no player bar, "LIVE" or time. Intro and screensaver both use it. Its first frame is the full card, so if this Chromecast doesn't animate pictures it shows the still card (as in 0.6.8) | Loop join measured (last→first frame differs no more than any two neighbours); preview video sent to the owner; needs a look on the TV. Guaranteed alternative: our own Cast receiver (one-time Google Cast developer registration by the owner) running `loop.html` live |


## 0.6.8: background and power

| Request | Done | Verified |
|---|---|---|
| "Let the app run in the background, no issues, for however long I need it" | A quiet notification keeps TheatreSync running whenever it's been opened (not only during movies), with **Remote** and **Quit** (movie controls during a movie). TVs keep being watched in the background with no time limit (was 10 minutes). Asks Android once to let it always run in the background (Motorola otherwise closes background apps) | Compiles; emulator crash/monkey tests; needs a day of normal use |
| On / off button on the remote | Remote → Projector & receiver: **Turn on** (Chromecast HDMI-CEC wake + network) and **Turn off** (network standby). Finds them on the Wi-Fi with no model numbers needed if they speak PJLink (most projectors), Denon/Marantz, Onkyo/Pioneer/Integra or Yamaha; remembered. If none is found it says how to switch on network control in their menus | `NetworkPowerTest` (7, fake projector and receivers); screenshot `remote2_power`; needs the real projector / receiver |


## 0.6.7: phone opening only on a fresh start

| Request | Done | Verified |
|---|---|---|
| "The intro when we open the app needs to be displayed only the first time. If the app is running in the background and I return it shouldn't show up" | `OpeningGate`: plays only when TheatreSync starts fresh (after it was closed / swiped away). Never when returning while it runs in the background (home screen, recent apps, after idle), from the notification or a share, or when Android rebuilds the screen of a background app. Still plays in full whatever the animation setting | `OpeningGateTest` (4); emulator simulation updated: fresh start shows it; returns after 36 s, quick switch, idle, background-killed reopen don't |


## 0.6.6: owner feedback on the TV card and YouTube headphones

| Report | Cause | Fix | Verified |
|---|---|---|---|
| Progress bar with "LIVE" on the TV; after ~31 s the screen blanks and the video starts again; wants the logo and words static, continuous | The Chromecast's own player draws a bar (and "LIVE") over any video, and the screensaver scenes each faded to black and replayed | Intro **and** screensaver are now the Signature **title card as a still picture** (1920×1080 PNG of the intro at its fullest glow). The TV shows pictures full-screen with no player bar, "LIVE" or time, and it just stays: no fade, no replay. Intro shows for 4.5 s before the film / YouTube / mirroring. The glow doesn't move in this mode (a moving glow with no bar needs our own Cast receiver, which needs a one-time Google Cast developer registration by the owner). Old video/stream files removed | `CastIntroTest`; needs a look on the TV |
| YouTube in headphones should feel like the TV's sound in the headphones, not the phone playing the video | The phone player was big and labelled as the phone playing | Card renamed **TV sound in your headphones**, shows **📺 TV → 🎧 headphones** and the sync; the YouTube player is a small **sound link** tile (YouTube requires it visible and at least 200×200 pixels); the corner chip on other pages is labelled 🎧 TV sound | Screenshot `casting4_youtubeHeadphones` |


## 0.6.5: "remove the black square void in the corner"

| Report | Cause | Fix | Verified |
|---|---|---|---|
| Black square floating in the bottom corner of Now casting | The YouTube headphones player (YouTube requires it to be visible) floated over every page, with a black page behind it, clipped and shadowed (Android can draw WebView video as a separate layer that goes black when clipped). It was also rebuilt on each page change and removed while the opening played | **One shared player** for as long as Sound in headphones is on (never reloaded; the opening no longer interrupts the sound). On **Now casting** it sits **inside the Sound in headphones card**, full width, not over the page. On other pages: a small framed, labelled player with **✕** to switch it off. The video's thumbnail shows underneath until YouTube draws (never a black void); no clipping or shadow on the video. Out-of-date notes fixed ("YouTube sound can't go to your headphones", "Want this in your headphones?" on YouTube) | Screenshot `casting4_youtubeHeadphones`; compiles. Whether the phone's YouTube video itself draws (not black) needs a look on the phone |


## 0.6.4: opening still not showing on 0.6.3 → simulated

| Report | Cause | Fix | Verified |
|---|---|---|---|
| Opening still not showing (owner did everything) | Most likely the phone's animation setting: with battery saver, "Remove animations" or the animation scale at 0, Android makes animations finish instantly, so the opening ended in one frame and vanished. The same explains the refresh arrow not completing its turns. The CI emulator also runs with animations off, which is why nothing caught it | Opening and refresh spinner run on the screen's own frame clock: always the full 6.6 s / two turns, whatever the animation setting. The app logs "Opening: shown / finished after N ms" | **`scripts/opening-sim.sh` in the CI emulator job**: installs the real app and, with animations **off** and **on**, opens it from the icon, comes back after 36 s, a 5 s quick switch, reopening after 36 s, phone idle then woken, and after Android closed it; fails unless the opening shows for ≥ 6 s exactly when it should |


## 0.6.3: "still not showing up when I open the app" (on 0.6.2)

| Report | Cause | Fix | Verified |
|---|---|---|---|
| Opening still doesn't show when opening TheatreSync | 0.6.2 played it only when the screen was built from scratch. Tapping the icon normally brings back the screen that's still running in the background (and after Android closes it, it's rebuilt "restored"), so it almost never played | `OpeningGate`: plays when the screen is built, **and every time TheatreSync comes back after you've been in other apps / the home screen for 30 s or more** (icon or recent apps), drawn over the app, which stays where you left it. Not when the phone just went idle (screen off/locked) with TheatreSync open, a quick switch under 30 s, rotation, picture-in-picture, the notification or a YouTube share | `OpeningGateTest` (7 cases: open, return after other apps, quick switch, idle screen, notification/share, rotation/PiP, every return). No emulator in the cloud box, so needs a look on the phone |


## 0.6.2: phone opening animation not showing

| Report | Cause | Fix | Verified |
|---|---|---|---|
| The Quantari opening on the phone no longer shows when the app is opened | It played once per app *process*. TheatreSync keeps running in the background (casting, notification, and since 0.5.9 watching the TVs for 10 minutes), so Android rarely ends it and the opening was skipped | Plays whenever the app is **opened fresh from its icon** (after closing / swiping it away). Coming back to it (recent apps, after idling, from the notification or a share) carries on where you left off. Not repeated on screen rotation | Compiles; needs a look on the phone |
| Owner's earlier feedback on the opening: "high definition and high clarity… mobile compatible… looks compressed… needs to look legit" (and "I don't care about the app size") | The logo picture was 952 px wide (the website has no bigger original) and drawn at ~850 px on the phone: barely 1:1, with soft, compressed letter edges. Text sizes followed the phone's font-size setting, so large text or a narrow phone could cut off "TheatreSync" | Logo rebuilt at 2× (1904 px): "QUANTARI" and "SOLUTIONS" re-drawn with crisp, smooth edges (compression wobble removed, no outline), swooshes and the soft fade into black kept (`docs/intro/upscale_wordmark.py`). All opening text is now sized from the screen width, so it looks the same on every phone and never clips. The TV intro already draws the logo smaller than the original (520 px), so it was already sharp | Screenshot tests: normal phone, phone at 200 % text (identical), tablet |


## 0.6.1: "Still no signature intro" (on 0.6.0)

| Report | Cause | Fix | Verified |
|---|---|---|---|
| No intro on 0.6.0 either | Most likely the owner's Chromecast doesn't play the live HLS stream from the phone (the screensaver uses the same method and has never been confirmed on this TV either). No report came back because the intro step gave up silently | The intro is the **same MP4 video the TV played in 0.5.8**, now with 2 s of black at the end, sent **marked as live** (the TV's player shows no progress bar or time for live). When the TV refuses or never starts it, a problem report is uploaded with what the TV said (player states, idle reason, status code). The YouTube/mirroring intro also uses the TV's remembered address and reports if it's skipped | `CastIntroTest`; needs HW-23. If the TV ignores "live" for an MP4 and shows the bar again, the report and photo will say so |


## 0.6.0: owner feedback on 0.5.9

| Report | Cause | Fix | Verified |
|---|---|---|---|
| "There is no intro being played" | 0.5.9 sent the intro as an animated picture; the Chromecast's media player doesn't animate those (a black still frame, the intro starts on black) | The intro is a short **live** stream (`IntroHls`): the original 1080p intro, then black until the film loads. Live video gets no progress bar or time on the TV (same as the screensaver). The list shows only the intro until the TV fetches it, so it always starts at the first frame | `IntroHlsTest`; played in Chromium with hls.js: starts at frame 1, no stalls, intro on screen; needs HW-23 on the TV |
| Refresh vibrates but the spinner doesn't complete a revolution | Material's spinner keeps growing and shrinking its arc, so it never reads as a full turn | Own spinner: a gold refresh arrow that turns with the pull, then spins exactly 2 whole turns at an even speed (1.8 s) and disappears | Compiles; needs a look on the phone |


## 0.5.9: owner feedback

| Request | Done | Verified |
|---|---|---|
| Intro looked like a video (time "0:04" and a progress bar on the TV) | The Chromecast's media player adds its controls to any video. The intro is now an **animated picture** (WebP, every frame stored whole so the glow has no blocks): pictures are shown full-screen with no controls, progress or time. Then the movie / YouTube / mirroring starts | Played in Chromium: smooth, right timing, ends on black; needs HW-23 on the TV |
| Ads: mute at the start, unmute as soon as they end, never forget | Mutes with the **Chromecast's own volume** (instant, volume level untouched) instead of YouTube's. Unmutes on whichever comes first: the advert's own end time (0.8 s early), YouTube saying it ended, the film playing again, a 75 s safety limit, losing contact (after 4 s), or the ad skipper stopping. Checks every 0.7 s while muted. Not touched while YouTube sound is in the headphones | `AdMute` unit tests (5) |
| Use GitHub ad blockers | Checked iSponsorBlockTV, castblock, sponsorblockcast, CastSponsorSkip: all skip skippable ads, mute unskippable ones and skip SponsorBlock segments — what TheatreSync does. None can remove unskippable ads on a Chromecast; a Chromecast with Google TV could run an ad-free YouTube app (e.g. SmartTube). Also from them: YouTube is revoking old TV pairing codes, so the app now fetches a fresh one automatically | — |
| The Frame not showing | Search now: both the DIAL and Samsung TV searches, 3× each, over the Wi-Fi network (never mobile data), Samsung TVs accepted without a DIAL address (they use :8080/ws/apps/), plus Samsung's own "Multiscreen" announcement (mDNS `_samsungmsf._tcp`); repeated every minute while the app is open; a TV that misses one search stays listed 3 minutes | `DialTest` (Samsung path); needs HW-23 |
| YouTube card takes ~10 s after coming back | The app kept watching TVs only while open. Now it keeps watching for 10 minutes after you leave, asks every TV for its status the moment you come back, and watches TVs seen in the last 45 s | Compiles; needs HW-23 |
| Notification Stop didn't stop YouTube | Stop only went through the TV watcher, which wasn't running in the background. Now it also opens its own connection to the TV (address remembered across restarts) and stops the app there | `CastStopperTest` with a fake Chromecast |
| Buzz when pull-to-refresh is released | Gentle confirm haptic on release | Compiles |
| Audit fix | Ad mute falls back to YouTube's own mute when the TV connection isn't open (app in the background 10+ minutes), so adverts are still muted and unmuted | Compiles |


## 0.5.8: owner feedback

| Request | Done | Verified |
|---|---|---|
| Equalizer changes should be heard instantly while watching | Cause: it used Android's shared audio effects (low priority; some phones' own sound processing overrides them) and sliders applied only on release. Now the equalizer runs **inside TheatreSync's player** (Media3 audio processor): biquad filters, changes take effect on the next few ms of sound, on any phone; sliders apply while dragging | Unit tests: boost heard at the band (±0.6 dB), next 100 ms already changed, no clipping at max boost |
| A lot more equaliser options | 10 bands (31 Hz–16 kHz), bass shelf, **23 presets** in three groups — Movies: Flat, Cinema, Action, Voices, Night, Horror, Animation; Sound: Bass boost, Deep bass, Treble, Loudness, Warm, Bright, Gaming; Music: Rock, Pop, Hip-hop, Electronic, Jazz, Classical, Acoustic, Vocals, Podcast — plus **Night mode** (compressor: quieter explosions, clearer whispers) and a soft limiter | Unit tests; screenshot |
| Remove "voice match lips", add a sync button aware of the sync | Player: **Sync** card with the live state ("In sync · 8 ms" / "Correcting…") and **Sync now** (instant re-lock). TapIn page: the lips buttons are replaced by a Sync card with live state and **Sync now**; any old manual delay is dropped | Instrumented test clicks Sync now; TapIn browser tests (12 + 16) pass |
| Logo must merge into black, no borders (TV especially) | The logo artwork's gold flames were solid up to the picture's edges (straight cut lines on a dark screen), and straight-line fades around it left a faint box. New soft-edged artwork: flame tips dissolve to fully transparent (letters untouched), shown on pure black with only round, soft glows; all straight masks removed. TV intro re-rendered; phone opening uses it too | Edge pixels alpha 0; brightened zooms of TV and phone frames show no edges |
| Online updates from the app instead of reinstalling from chat | Settings → About shows the version and, when there's a newer one, "Update available · x.y" with **Update**: downloads in the app (progress), checks it's exactly the published file (SHA-256), hands it to Android's installer (Android asks "Update this app?" once; first time it also asks to allow TheatreSync to install updates). Checked quietly when the app opens. Updates are published only on the owner's "release", with `scripts/publish-release.sh`, which refuses any APK not signed with the app's key. Downloads come from a **public releases-only repository** (`quantari-solutions/theatresync-releases`: APKs + `latest.json`, never code) because this code repository is private — **the owner creates it once**. Microphone permission removed (subtitles no longer need it) | `UpdateInfo` unit tests; screenshot `about1`; publish dry run (key check passed). Needs the releases repo, then HW-22 |
| YouTube page glitchy while casting: TV choice unticks, link goes blank | Causes: the page kept the link/choice only while on screen, and the TV list briefly emptied or reshuffled while the Chromecast switched to YouTube (Android drops and re-adds it). Now the link and chosen TV live at app level, the TV is matched by name, and TVs stay listed through 45 s gaps (also keeps casts in progress able to reach the TV). New **Connecting to <TV>…** card with spinner and step progress; the form is locked while it works | Screenshot `youtube1_connecting` |
| Still no YouTube sound in headphones (on 0.5.5) | 0.5.5 had no way to do it (the feature arrived in 0.5.7). Now also: the **Audio sheet** has a "YouTube on <TV>" section with **Headphones (this phone)** and **Room speakers** checkboxes, and casting YouTube from TheatreSync with headphones connected switches headphones on by itself (unless switched off last time) | Compiles; needs HW-21 |
| Crash reports uploaded after logging in again | Backlog from 0.4.7/0.5.0: headphone player stuck buffering (fixed in 0.5.0), TV stalls on slow Wi-Fi (smart start, 0.5.7), YouTube link aborted by a Wi-Fi blip (#33: YouTube steps now retry 3×), headphones found in call mode (SCO) once (#34, noted) | — |
| Second phone joins headphone sync for what's playing, any time | YouTube: Now casting → Sound in headphones on any phone. A movie cast by another phone with TheatreSync: Now casting shows **Listen in my headphones** (opens its TapIn page, in step). Other phones' screensavers no longer show as "now casting" | Compiles; needs a second phone |
| Updates without making a separate repo | The app checks both a releases-only public repo and this repo's `releases/` folder, so making this repository public also works | — |
| Crash reports stopped reaching GitHub: keeps getting logged out | Cause: GitHub App sign-ins last 8 hours and come with a 6-month refresh token, which the app threw away, so after 8 hours every upload got "login expired". Now the refresh token is kept (encrypted) and the app renews the sign-in by itself before it runs out (on app start and before uploading) and once more if GitHub answers 401; each renewal renews the 6 months too, so it stays connected as long as the app is opened at least every 6 months. Device-flow renewals need no secret (GitHub docs). Also honours GitHub's "slow down" while waiting for approval | Instrumented tests with a fake GitHub: renewal before expiry, renewal + retry after 401 |
| Copyable GitHub sign-in code | The code is copied automatically when shown, with a **Copy code** button; **Open GitHub** copies it again and opens the page, so it's just paste | Compiles; needs HW-22 |
| Delete crash reports after 69 days | Reports older than 69 days are deleted from the phone when the app opens (noted on the Crash reports page) | Instrumented test |
| Use the Signature Intro for the screensaver | Each screensaver scene is now the Signature Intro (15 s) with its own random timing, glow swells, light sweeps and sparks, rising from and dissolving into pure black; 12 scenes, endless random order | Scene ends are pure black (pixel max 0) |
| Screensaver felt like a short GIF loop | Now an **endless live stream** from the phone (HLS): 12 different 15 s scenes (each with its own random glow swells, light sweeps, drift and sparks), played in a fresh random order every round, never the same scene twice in a row; timestamps re-stamped on the fly so the TV sees one continuous programme. Scenes start and end in the same calm frame, so joins are invisible | `EndlessHls`/`TsRetimer` unit tests; real scenes streamed by the app's server and played in a browser (hls.js) across many scene changes |
| YouTube casting needs improvement | Faster start: YouTube's remote link is prepared during the intro (the TV's YouTube ID is remembered); the start shows its steps (step 1 of 3 intro, 2 opening YouTube, 3 starting the video). Sound in headphones: now a small YouTube player that stays on screen across the whole app (and becomes the picture-in-picture window), remembered for the next YouTube cast, and follows pauses/skips on the TV instantly (YouTube's remote-control events trigger an immediate status refresh) | Compiles; sync logic as in 0.5.7; needs HW-21 |


## 0.5.7: owner feedback on 0.5.5

| Request | Done | Verified |
|---|---|---|
| Auto-generated subtitles not working | Cause (most likely): the generator relied on Google's built-in offline speech recognizer, which many phones (including Motorola) don't have, and it hid the recognizer's errors. Now uses **Vosk** (open-source offline recognizer, in the app): any phone, word-level timing so lines appear exactly when spoken, dialogue taken mainly from the centre channel of surround sound. Each language's speech pack (40–100 MB) downloads once from the Vosk project's site, with progress; nothing is uploaded. No microphone permission any more. APK 18.7 MB (native code now stored compressed) | Vosk + English pack on a synthetic clip: words and timings right; `SpeechCues` unit tests; on-emulator test that the native engine loads; needs HW-20 on a real movie |
| Lag at the start until pause → wait → play | **Smart start**: the movie loads on the TV paused; the app counts how much of it the TV has fetched (converted to seconds of film by the file's bitrate) and presses play once the TV holds 20 s, or 6 s when Wi-Fi is clearly faster than the film, or after 45 s at most. "Getting the TV ready… 60% · about 8 s" on the player. Headphones wait at the same spot and start with the picture | `StartGate` unit tests; needs HW-20 |
| YouTube: no sound in headphones | Before: impossible by design (the TV's YouTube app plays the sound; the phone never has it). Now: Now casting → **Sound in headphones** plays the same video on the phone in YouTube's official embedded player (shown on screen, as YouTube requires), sound to the headphones, kept in step with the TV (position followed from the TV's status between updates; one calibrating jump, then jumps only past 0.25 s, at most every 5 s; output delay allowed for). The TV is muted while on; if YouTube refuses a video on the phone (owner disabled embedding), the TV's sound comes back. Works for YouTube cast from any app (ReVanced too) | Sync logic tested in Chromium with a stand-in player (YouTube blocks data-centre browsers): within 2 ms of target, pause and +60 s jump followed. Real YouTube needs HW-20 |
| Screensaver instead of the grey cast screen | Connected with nothing playing (or after a movie stops): the TV plays a silent, seamless 20 s Quantari · TheatreSync loop (breathing gold glow, slow sheen, drifting sparks), repeated, until a movie or YouTube starts. Settings → Experimental → TV screensaver (on). Served from the phone, so TheatreSync must stay open in the background | Loop seam 58 dB; needs HW-20 |
| Projector / AV receiver on-off, brightness from the remote | **Wake projector & receiver** on the remote (HDMI-CEC One Touch Play via the Chromecast). Turning off and brightness can't be done through a Chromecast (no app API); possible over the devices' own network control (e.g. PJLink for projectors) once their models are known | needs HW-20 |
| Scrap the ±ms sync tool, auto sync only | Manual "Sound is early/late" and ±10/50 ms buttons removed (an old saved adjustment is dropped). Headphone settings → **Auto sync**: live "in step within X ms" (checked 4×/s, corrected automatically) and a one-tap **Re-sync now** | Screenshots |


## 0.5.6: opening looks professional

| Request | Done | Verified |
|---|---|---|
| Opening looked compressed and not legit (owner screenshot) | Cause: the 16:9 TV clip was stretched to fill a tall phone screen, and the clip came from a lossy screen recording. Phone opening is now drawn by the app (Inter text at the phone's resolution, lossless logo, gold sheen, same timing), laid out for portrait; tap to skip | Screenshot test `opening1` |
| Smoother, let the glow lead (owner) | Slower 6.6 s timeline with gentle easing: the glow rises first, the sheen crosses the logo slowly, the title's glow blooms in, then the whole glow swells once before the fade. Phone animation runs at the screen's own refresh rate; TV clip 30 fps (safe for older Chromecasts) | Screenshot at peak glow; TV key frames checked |
| TV intro quality | Re-rendered frame by frame from `card.html` (lossless PNGs) and encoded at High@4.1 CRF 12: 43 dB PSNR vs the source frames. Logo letters no longer fade at the edges (phone and TV) | ffprobe; frame comparison |

## 0.5.5: host controls TapIn guests

| Request | Done | Verified |
|---|---|---|
| Host controls guests using the web page | Audio sheet → **Listening now**: each guest's name, sync and status, with **Sound on** (mute), **volume** (− / +) and **Remove**. Changes reach the guest within ~1 s (their page reports every second and gets the controls back). iPhones ignore page volume (mute works) | `Guests` + route unit tests; browser test: mute 0.7 s, unmute/volume ≤ 1 s, remove 0.1 s |
| Leaving/joining shows immediately | Guest appears on the host the moment they tap **Start listening**; **Stop listening** or closing the page sends `/leave` (sendBeacon) → gone at once. A page that disappears without it is dropped after 4 s; a locked screen (still listening) after 10 min | Browser test: join 4 ms, Stop 46 ms, page closed 6 ms |
| Sound starts right away | Unchanged fast start (sound-only stream from 0.5.4); Start → sound in ~0.1 s in the browser test | Browser test |
| Sync for all outputs on the phone | Your playing headphones show "In step · +12 ms" in Audio sources; each guest shows theirs | Screenshot `guests1`; needs HW-19 |
| Guest page shows only their own Bluetooth device | "Sync: +12 ms on AirPods Pro". Phone browsers don't tell web pages which headphones are connected (only after a microphone prompt, which can lower Bluetooth quality on iPhones, so not used): the name is read where the browser already allows it, else the guest types it once (remembered). Host list shows "Priya · AirPods Pro" | Browser test |
| Fix found while testing | A report already on its way when a guest closed the page could add them back for 4 s; late reports from a page that left are now ignored (fresh id per Start) | Unit test; browser test 3× clean |
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
