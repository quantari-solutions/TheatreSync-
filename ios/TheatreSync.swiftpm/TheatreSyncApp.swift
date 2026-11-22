import SwiftUI

@main
struct TheatreSyncApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @StateObject private var session = ListenerSession()
    @StateObject private var browser = HostBrowser()
    @StateObject private var cast = CastController()
    @StateObject private var power = NetworkPower()
    @StateObject private var updates = UpdateCheck()
    /// The opening plays once each time the app starts fresh (not when coming back from the background).
    @State private var opening = true

    var body: some View {
        // The opening alone first (nothing drawn over or under it), then the app. Once per fresh start.
        Group {
            if opening {
                OpeningView { opening = false }
            } else {
                NavigationStack {
                    if session.status == .idle {
                        HomeView(cast: cast, power: power, updates: updates, session: session, browser: browser)
                    } else {
                        ListeningView(session: session)
                    }
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .tint(Q.primary)
    }
}
