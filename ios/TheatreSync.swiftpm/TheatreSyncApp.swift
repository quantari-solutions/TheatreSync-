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

    var body: some View {
        NavigationStack {
            if session.status == .idle {
                JoinView(session: session, browser: browser)
            } else {
                ListeningView(session: session)
            }
        }
        .preferredColorScheme(.dark)
        .tint(.yellow)
    }
}
