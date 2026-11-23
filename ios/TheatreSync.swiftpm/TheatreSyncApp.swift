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
    @StateObject private var samsung = SamsungFinder()
    @StateObject private var youtube = YouTubeCaster()
    @StateObject private var headphones = HeadphonesPlayer()
    @StateObject private var movie = MovieCaster()
    /// The opening plays once each time the app starts fresh (not when coming back from the background).
    @State private var opening = true
    @State private var path: [Page] = []
    private let feed = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    var body: some View {
        // The opening alone first (nothing drawn over or under it), then the app. Once per fresh start.
        Group {
            if opening {
                OpeningView { opening = false }
            } else {
                NavigationStack(path: $path) {
                    HomeView(path: $path)
                    .navigationDestination(for: Page.self) { page in
                        switch page {
                        case .nowCasting: NowCastingPage()
                        case .remote: RemotePage()
                        case .youtube: YouTubePage()
                        case .settings: SettingsPage()
                        case .listen: JoinView(session: session, browser: browser)
                        case .listening: ListeningView(session: session)
                        case .movie: MoviePage()
                        case .headphones: HeadphonesPage()
                        case .mirror: MirrorPage()
                        case .experimental: ExperimentalPage()
                        case .multiAudio: MultiAudioPage()
                        }
                    }
                }
            }
        }
        .environmentObject(cast)
        .environmentObject(power)
        .environmentObject(updates)
        .environmentObject(samsung)
        .environmentObject(youtube)
        .environmentObject(headphones)
        .environmentObject(session)
        .environmentObject(movie)
        // Joining a movie opens the Listening page (with a back button: home is always one tap away).
        .onChange(of: session.status == .idle) { idle in
            if !idle, path.last != .listening { path = [.listening] }
            if idle, path.last == .listening { path.removeLast() }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .tint(Q.primary)
        .onAppear {
            headphones.target = { [weak c = cast, weak y = youtube] in Self.youTubeNow(c, y) }
            // Simulator check: open straight onto a page (launch argument -tsPage settings|youtube|remote|nowCasting).
            if let name = UserDefaults.standard.string(forKey: "tsPage"), let page = Page(name: name) { opening = false; path = [page] }
        }
        .onReceive(feed) { _ in
            // TapIn guests follow the TV's YouTube through this phone.
            let t = Self.youTubeNow(cast, youtube)
            TitleCardServer.shared.setYouTubeState(YouTubeListen.stateJson(videoId: t?.videoId, position: t?.position ?? 0, playing: t?.playing ?? false))
        }
    }

    /// Where the TV's YouTube is now: the Chromecast's own report, or YouTube's events for a Samsung TV.
    @MainActor
    static func youTubeNow(_ cast: CastController?, _ youtube: YouTubeCaster?) -> (videoId: String, position: Double, playing: Bool)? {
        if let t = cast?.youTubeNow { return t }
        if let y = youtube, case .samsung? = y.target, let v = y.castVideoId, let p = y.samsungNow { return (v, p, y.samsungPlaying) }
        return nil
    }
}
