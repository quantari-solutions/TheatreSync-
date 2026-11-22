# iOS on iPad with Swift Playgrounds

The iOS app lives in [`ios/TheatreSync.swiftpm`](../ios/TheatreSync.swiftpm), a Swift
Playgrounds **App** package. It uses only Apple frameworks (SwiftUI, Foundation,
AVFoundation) — no Xcode-only dependencies.

> In the cloud, the shared protocol/sync code (`Shared/`) is compiled and tested with Swift
> for Linux. The **app itself** (SwiftUI, AVFoundation, Network) is first compiled when you
> open it in Swift Playgrounds. Please report any compile error back verbatim.

Since 0.7.0 the iOS app is the **full TheatreSync, part 1**: it finds your Chromecast (Bonjour) and talks to it
directly (Cast v2 over TLS, no Google library): Now casting with play/pause, ±10 s, volume, mute and Stop; projector &
receiver Turn on / Turn off (HDMI-CEC wake + PJLink, Denon/Marantz, Onkyo/Pioneer/Integra, Yamaha over the Wi-Fi); the
living title card and screensaver on the TV (served from the iPhone/iPad as an animated picture, no player bar); the
opening animation (once per fresh start); listen along (joins a movie the Android phone is playing). Part 2: YouTube
casting and the TV's sound in your headphones. Every change is compiled with Xcode and run on simulated iPhone and iPad
on GitHub (`scripts/ios-sim.sh`) before a zip is made.

Updates: Settings isn't allowed to install them on iOS. The app's About card says when a newer version is out
(`releases/ios-latest.json`) with a link to the new zip (`releases/ios/TheatreSync-iOS.zip`). Automatic updates need
TestFlight (Apple Developer account). The first time, iOS asks to allow **Local Network**.
Tap **Allow**, because that's how it finds the phone.

## Get the package onto the iPad

Option A — Files app (simplest):
1. On the iPad, open the repo on github.com → **Code → Download ZIP**.
2. In **Files**, tap the zip to extract it.
3. Move the `ios/TheatreSync.swiftpm` folder into **On My iPad → Playgrounds**
   (or iCloud Drive → Playgrounds).
4. Open **Swift Playgrounds** — `TheatreSync` appears in *My Apps*. Tap it, then ▶ Run.

Option B — Working Copy app (git client) can clone the repo and share the
`.swiftpm` folder to Swift Playgrounds, so updates are a pull instead of a re-download.

## Updating after a change

Replace the whole `TheatreSync.swiftpm` folder with the new one. Don't edit files on
the iPad; ask Claude to make changes in the repo instead, so GitHub stays the source of truth.

## Files

| File | Role |
|---|---|
| `Package.swift` | App manifest (Swift Playgrounds format, iOS 17+) |
| `TheatreSyncApp.swift` | App entry point |
| `JoinView.swift` | Finds movies on the Wi-Fi; manual address entry |
| `ListeningView.swift` | Now-listening screen with lip-sync buttons |
| `ListenerSession.swift` | Plays the sound and keeps it in step with the TV |
| `HostBrowser.swift` | Bonjour discovery of the Android phone |
| `DiagnosticsView.swift` | "Check headphones": audio route, latency, protocol self-test |
| `Shared/SyncMessage.swift` | Protocol v1 models, a mirror of `contracts/` |
| `Shared/ClockSync.swift` | Same clock algorithm as Android `sync-core` |
| `Shared/Sync.swift` | Same drift-correction rules as Android |
