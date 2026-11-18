# iOS on iPad with Swift Playgrounds

The iOS app lives in [`ios/TheatreSync.swiftpm`](../ios/TheatreSync.swiftpm), a Swift
Playgrounds **App** package. It uses only Apple frameworks (SwiftUI, Foundation,
AVFoundation) — no Xcode-only dependencies.

> In the cloud, the shared protocol/sync code (`Shared/`) is compiled and tested with Swift
> for Linux. The **app itself** (SwiftUI, AVFoundation, Network) is first compiled when you
> open it in Swift Playgrounds. Please report any compile error back verbatim.

The iOS app is a **listener**: it joins a movie that the Android phone is playing and plays
the sound in this device's headphones. The first time, iOS asks to allow **Local Network**.
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
