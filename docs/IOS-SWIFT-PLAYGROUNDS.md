# iOS on iPad with Swift Playgrounds

The iOS app lives in [`ios/TheatreSync.swiftpm`](../ios/TheatreSync.swiftpm), a Swift
Playgrounds **App** package. It uses only Apple frameworks (SwiftUI, Foundation,
AVFoundation) — no Xcode-only dependencies.

> Cloud CI only **statically checks** these files. They are first compiled when you
> open them in Swift Playgrounds. Please report any compile error back verbatim.

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
| `DiagnosticsView.swift` | Milestone 0 screen: audio route, latency, protocol self-test |
| `Shared/SyncMessage.swift` | Protocol v1 models — mirror of `contracts/` |
| `Shared/ClockSync.swift` | Same clock algorithm as Android `sync-core` |
