import SwiftUI

/// The home page, laid out like the Android app: brand bar with the ☰ menu, Quick watch tiles, your TVs,
/// what's on now (tap for its page), and Movie night. Every other page opens with a back button.
struct HomeView: View {
    @EnvironmentObject var cast: CastController
    @EnvironmentObject var power: NetworkPower
    @EnvironmentObject var updates: UpdateCheck
    @EnvironmentObject var samsung: SamsungFinder
    @EnvironmentObject var youtube: YouTubeCaster
    @EnvironmentObject var session: ListenerSession
    @EnvironmentObject var movie: MovieCaster
    @AppStorage("screensaver") private var screensaver = true
    @Binding var path: [Page]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                topBar
                if let u = updates.available {
                    Button { path.append(.settings) } label: {
                        Label("Update available · \(u.version)", systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity)
                    }.buttonStyle(GoldButtonStyle())
                }
                if session.status != .idle {
                    Button { path.append(.listening) } label: {
                        QCard(highlighted: true) {
                            HStack {
                                Image(systemName: "headphones").foregroundStyle(Q.primary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("LISTENING IN YOUR HEADPHONES").font(.caption2.weight(.bold)).foregroundStyle(Q.success)
                                    Text(session.movieTitle.isEmpty ? session.hostName : session.movieTitle).font(.headline).foregroundStyle(Q.text)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(Q.primary)
                            }
                        }
                    }.buttonStyle(.plain)
                }
                if cast.connected, let a = cast.activity, !cast.isShowingTitleCard { nowCastingRow(a) }
                else if let tv = youtube.loungeTV { samsungRow(tv) }
                if remoteCastAway {
                    QCard(highlighted: true) {
                        Text("You're away from home").font(.headline).foregroundStyle(Q.text)
                        Text("Cast YouTube to \(YouTubeCaster.awayTVs.keys.sorted().joined(separator: ", ")) over the internet.").font(.footnote).foregroundStyle(Q.textMuted)
                        Button { path.append(.remoteCast) } label: { Label("Cast from here", systemImage: "globe").frame(maxWidth: .infinity) }
                            .buttonStyle(GoldButtonStyle())
                    }
                }
                quickWatch
                tvCard
                movieNight
                Text("TheatreSync · by Quantari Solutions").font(.caption2).foregroundStyle(Q.textFaint).frame(maxWidth: .infinity)
            }
            .padding(20)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
        }
        .background(GridBackground())
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { cast.refresh(); samsung.start(); power.search(force: true); updates.check() }
        .onAppear { cast.startBrowsing(); samsung.start(); power.search(); updates.check() }
        .onChange(of: cast.connected) { maybeScreensaver() }
        .onChange(of: cast.activity) { maybeScreensaver() }
    }

    /// Out of the house: no Chromecast on this Wi-Fi, but TVs remembered for Remote cast.
    private var remoteCastAway: Bool {
        (UserDefaults.standard.object(forKey: "remoteCast") as? Bool ?? true) && cast.tvs.isEmpty && !YouTubeCaster.awayTVs.isEmpty && youtube.loungeTV == nil
    }

    /// Connected with nothing playing: the TV shows the living title card (when the screensaver is on).
    private func maybeScreensaver() {
        guard screensaver, cast.connected, cast.activity == nil else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            if screensaver, cast.connected, cast.activity == nil { cast.showTitleCard() }
        }
    }

    private var topBar: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Eyebrow(text: "Quantari")
                GradientTitle(text: "TheatreSync", size: 30)
            }
            Spacer()
            Menu {
                Button { path.append(.youtube) } label: { Label("YouTube", systemImage: "play.rectangle.fill") }
                Button { path.append(.remote) } label: { Label("Remote", systemImage: "appletvremote.gen4") }
                Button { path.append(.movie) } label: { Label("Play a movie", systemImage: "film") }
                Button { path.append(.mirror) } label: { Label("Show your phone on the TV", systemImage: "rectangle.on.rectangle") }
                Button { path.append(.headphones) } label: { Label("Headphones", systemImage: "headphones.circle") }
                if cast.connected { Button { path.append(.nowCasting) } label: { Label("Now casting", systemImage: "tv") } }
                Button { path.append(.listen) } label: { Label("Listen along", systemImage: "headphones") }
                Divider()
                Button { path.append(.settings) } label: { Label("Settings", systemImage: "gearshape") }
            } label: {
                Image(systemName: "line.3.horizontal").font(.title2.weight(.semibold)).foregroundStyle(Q.primary)
                    .frame(width: 48, height: 48)
                    .background(Q.card, in: Circle())
                    .overlay(Circle().stroke(Q.borderAccent, lineWidth: 1))
            }
            .accessibilityLabel("Menu")
        }
    }

    private func nowCastingRow(_ a: CastActivity) -> some View {
        Button { path.append(.nowCasting) } label: {
            OrbitCard(playing: a.isPlaying) {
                HStack(spacing: 14) {
                    Thumb(url: a.imageUrl ?? (a.appId == CastStatus.appYouTube ? a.contentId.map { "https://i.ytimg.com/vi/\($0)/hqdefault.jpg" } : nil))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("NOW CASTING · \(a.appName.uppercased())").font(.caption2.weight(.bold)).foregroundStyle(Q.success)
                        Text(a.title ?? a.appName).font(.headline).foregroundStyle(Q.text).lineLimit(2)
                        Text(cast.selected?.name ?? "TV").font(.caption).foregroundStyle(Q.textMuted)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Q.primary)
                }
            }
        }.buttonStyle(.plain)
    }

    private func samsungRow(_ tv: SamsungTV) -> some View {
        Button { path.append(.youtube) } label: {
            OrbitCard(playing: youtube.samsungPlaying) {
                HStack(spacing: 14) {
                    Thumb(url: youtube.castVideoId.map { "https://i.ytimg.com/vi/\($0)/hqdefault.jpg" })
                    VStack(alignment: .leading, spacing: 3) {
                        Text("NOW CASTING · YOUTUBE").font(.caption2.weight(.bold)).foregroundStyle(Q.success)
                        Text(tv.name).font(.headline).foregroundStyle(Q.text)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Q.primary)
                }
            }
        }.buttonStyle(.plain)
    }

    private var quickWatch: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Quick watch").font(.title3.weight(.bold)).foregroundStyle(Q.text)
            HStack(spacing: 12) {
                Tile(title: "YouTube", subtitle: "Cast a link", icon: "play.rectangle.fill") { path.append(.youtube) }
                Tile(title: "Mirror", subtitle: "Phone or laptop on the TV", icon: "rectangle.on.rectangle") { path.append(.mirror) }
            }
            HStack(spacing: 12) {
                Tile(title: "Now casting", subtitle: cast.connected ? (cast.selected?.name ?? "TV") : "Pick your TV below", icon: "tv") { path.append(.nowCasting) }
                Tile(title: "Remote", subtitle: "TV, projector, receiver", icon: "appletvremote.gen4") { path.append(.remote) }
            }
            HStack(spacing: 12) {
                Tile(title: "Headphones", subtitle: "Pair, test, sync", icon: "headphones") { path.append(.headphones) }
                Tile(title: "Listen along", subtitle: "A movie on another phone", icon: "person.2.wave.2") { path.append(.listen) }
            }
        }
    }

    private var tvCard: some View {
        QCard(highlighted: cast.connected) {
            Text("Your TVs").font(.title3.weight(.bold)).foregroundStyle(Q.text)
            if cast.tvs.isEmpty && samsung.tvs.isEmpty {
                StatusPill(text: "Looking on the Wi-Fi…", color: Q.primary, pulsing: true)
                Text("Same Wi-Fi as your TV, and allow Local Network when iOS asks.").font(.footnote).foregroundStyle(Q.textMuted)
            }
            ForEach(cast.tvs) { tv in
                Button { cast.select(tv) } label: {
                    HStack {
                        Image(systemName: "tv").foregroundStyle(Q.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tv.name).foregroundStyle(Q.text).lineLimit(1)
                            Text(cast.selected == tv && cast.isGoogleTV == true ? "Chromecast with Google TV · can be the home hub"
                                 : (cast.selected == tv && cast.isGoogleTV == false ? "Chromecast · YouTube from away" : "Chromecast"))
                                .font(.caption).foregroundStyle(Q.textFaint)
                        }
                        Spacer()
                        if cast.selected == tv {
                            StatusPill(text: cast.connected ? "Connected" : "Connecting…", color: cast.connected ? Q.success : Q.primary, pulsing: !cast.connected)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
            ForEach(samsung.tvs) { tv in
                Button { youtube.chosen = .samsung(tv); path.append(.youtube) } label: {
                    HStack {
                        Image(systemName: "photo.artframe").foregroundStyle(Q.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tv.name).foregroundStyle(Q.text).lineLimit(1)
                            Text(tv.ip == nil ? "Samsung · finding its address…" : "Samsung · YouTube").font(.caption).foregroundStyle(Q.textFaint)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Q.textFaint)
                    }
                    .padding(.vertical, 6)
                }
            }
            Text("Samsung TV missing? Add it by its address in Settings.").font(.caption).foregroundStyle(Q.textFaint)
        }
    }

    private var movieNight: some View {
        QCard {
            Text("Movie night").font(.title3.weight(.bold)).foregroundStyle(Q.text)
            Step(n: 1, text: "Pick your TV above.", done: cast.connected)
            Step(n: 2, text: "Projector and receiver on (Remote).", done: false)
            Step(n: 3, text: "Pick a movie: picture on the TV, sound in your headphones.", done: movie.playing != nil)
            Button { path.append(.movie) } label: { Label("Play a movie", systemImage: "film").frame(maxWidth: .infinity) }
                .buttonStyle(GoldButtonStyle())
            Button { path.append(.remote) } label: { Label("Open the remote", systemImage: "power").frame(maxWidth: .infinity) }
                .buttonStyle(GhostButtonStyle())
        }
    }
}

/// The pages reached from home (each shows a back button).
enum Page: Hashable {
    case nowCasting, remote, youtube, settings, listen, listening, movie, headphones, mirror, experimental, multiAudio, remoteCast, youtubeBrowse

    init?(name: String) {
        switch name {
        case "nowCasting": self = .nowCasting
        case "remote": self = .remote
        case "youtube": self = .youtube
        case "settings": self = .settings
        case "listen": self = .listen
        case "movie": self = .movie
        case "headphones": self = .headphones
        case "mirror": self = .mirror
        case "experimental": self = .experimental
        case "multiAudio": self = .multiAudio
        case "remoteCast": self = .remoteCast
        case "youtubeBrowse": self = .youtubeBrowse
        default: return nil
        }
    }
}

struct Tile: View {
    let title: String
    let subtitle: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: icon).font(.title2).foregroundStyle(Q.primary)
                Text(title).font(.headline).foregroundStyle(Q.text)
                Text(subtitle).font(.caption).foregroundStyle(Q.textMuted).lineLimit(1)
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 112, alignment: .topLeading)
            .background(LinearGradient(colors: [Q.card, Q.backgroundAlt], startPoint: .top, endPoint: .bottom), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Q.borderAccent, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct Step: View {
    let n: Int
    let text: String
    let done: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().fill(done ? Q.success.opacity(0.2) : Q.primary.opacity(0.12)).frame(width: 28, height: 28)
                if done { Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(Q.success) }
                else { Text("\(n)").font(.caption.weight(.bold)).foregroundStyle(Q.primary) }
            }
            Text(text).foregroundStyle(Q.textMuted)
        }
    }
}

struct Thumb: View {
    let url: String?

    var body: some View {
        Group {
            if let s = url, let u = URL(string: s) {
                AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { Q.backgroundAlt }
            } else {
                ZStack { Q.backgroundAlt; Image(systemName: "tv").foregroundStyle(Q.primary) }
            }
        }
        .frame(width: 96, height: 54)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
