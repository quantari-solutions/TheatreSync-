import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

/// A page with the app's look and a back button (the system one, tinted gold).
struct PageScaffold<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) { content() }
                .padding(20)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
        }
        .background(GridBackground())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Q.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }
}

// MARK: - Now casting

struct NowCastingPage: View {
    @EnvironmentObject var cast: CastController
    @EnvironmentObject var headphones: HeadphonesPlayer
    @State private var dragging: Double?
    @State private var pendingSeek: (target: Double, at: Date)?

    var body: some View {
        PageScaffold(title: "Now casting") {
            if !cast.connected {
                QCard { Text("Pick your TV on the home page first.").foregroundStyle(Q.textMuted) }
            } else if let a = cast.activity, !cast.isShowingTitleCard {
                QCard(highlighted: a.isPlaying) {
                    if headphones.on, headphones.videoId != nil, a.appId == CastStatus.appYouTube {
                        HeadphonesWebView(player: headphones).aspectRatio(16 / 9, contentMode: .fit)
                    } else if let img = a.imageUrl ?? (a.appId == CastStatus.appYouTube ? a.contentId.map { "https://i.ytimg.com/vi/\($0)/hqdefault.jpg" } : nil), let u = URL(string: img) {
                        AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { Q.backgroundAlt }
                            .frame(maxWidth: .infinity).aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    Text(a.appName).font(.caption.weight(.bold)).foregroundStyle(Q.success)
                    Text(a.title ?? a.appName).font(.title3.weight(.bold)).foregroundStyle(Q.primary).lineLimit(3)
                    if let s = a.subtitle { Text(s).font(.subheadline).foregroundStyle(Q.textMuted) }
                    if let d = a.durationSec, d > 0, a.canControl { seekBar(a, duration: d) }
                    if a.canControl { transport(a) }
                    Button { cast.stopCasting() } label: { Label("Stop casting", systemImage: "stop.fill").frame(maxWidth: .infinity) }
                        .buttonStyle(GhostButtonStyle())
                }
                VolumeCard()
                if a.appId == CastStatus.appYouTube { HeadphonesCard(); TapInCard() }
            } else {
                QCard {
                    Text(cast.selected?.name ?? "TV").font(.title3.weight(.bold)).foregroundStyle(Q.text)
                    Text(cast.isShowingTitleCard ? "Showing the TheatreSync title card." : "Nothing playing. Cast from YouTube or any app.").foregroundStyle(Q.textMuted)
                }
                VolumeCard()
            }
        }
    }

    private func seekBar(_ a: CastActivity, duration: Double) -> some View {
        let now = cast.youTubeNow?.position ?? a.currentTimeSec ?? 0
        let loading: Bool = {
            guard let p = pendingSeek else { return false }
            return abs(now - p.target) > 3 && Date().timeIntervalSince(p.at) < 15
        }()
        let shown = dragging ?? (loading ? pendingSeek!.target : now)
        return VStack(spacing: 4) {
            Slider(value: Binding(get: { min(shown, duration) }, set: { dragging = $0 }), in: 0...duration, onEditingChanged: { editing in
                if !editing, let to = dragging {
                    cast.seek(to: to); pendingSeek = (to, Date()); dragging = nil
                }
            }).tint(Q.primary)
            HStack {
                Text(clock(shown) + (loading ? " · Loading…" : "")).foregroundStyle(loading ? Q.primary : Q.textMuted)
                Spacer()
                Text(clock(duration)).foregroundStyle(Q.textMuted)
            }.font(.caption.monospacedDigit())
        }
    }

    private func transport(_ a: CastActivity) -> some View {
        HStack(spacing: 14) {
            Button { cast.seek(by: -10) } label: { Image(systemName: "gobackward.10").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
            Button { cast.playPause() } label: { Image(systemName: a.isPlaying ? "pause.fill" : "play.fill").font(.title2).frame(maxWidth: .infinity) }
                .buttonStyle(GoldButtonStyle()).accessibilityLabel(a.isPlaying ? "Pause" : "Play")
            Button { cast.seek(by: 10) } label: { Image(systemName: "goforward.10").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
        }
    }
}

func clock(_ s: Double) -> String {
    let t = Int(max(0, s)); let h = t / 3600, m = (t % 3600) / 60, sec = t % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
}

struct VolumeCard: View {
    @EnvironmentObject var cast: CastController

    var body: some View {
        QCard {
            HStack {
                Text("TV volume").font(.headline).foregroundStyle(Q.text)
                Spacer()
                Button(cast.muted ? "Unmute" : "Mute") { cast.setMuted(!cast.muted) }.buttonStyle(GhostButtonStyle())
            }
            Slider(value: Binding(get: { cast.volume }, set: { cast.setVolume($0) }), in: 0...1).tint(Q.primary)
            Text("The Chromecast's volume (room speakers).").font(.footnote).foregroundStyle(Q.textMuted)
        }
    }
}

// MARK: - TV sound in your headphones (YouTube)

struct HeadphonesCard: View {
    @EnvironmentObject var headphones: HeadphonesPlayer

    var body: some View {
        QCard(highlighted: headphones.on) {
            Toggle(isOn: Binding(get: { headphones.on }, set: { headphones.setOn($0) })) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("TV sound in your headphones").font(.headline).foregroundStyle(Q.text)
                    Text("Plays the same video on this phone, in step with the TV, with its sound in your headphones.").font(.footnote).foregroundStyle(Q.textMuted)
                }
            }.tint(Q.primary)
            if headphones.on {
                Text(headphones.status).font(.footnote.weight(.semibold)).foregroundStyle(Q.primary)
                Text("Connect your headphones to this phone in iOS Settings → Bluetooth. Keep the small player on screen: YouTube needs it visible to give the sound.")
                    .font(.footnote).foregroundStyle(Q.textFaint)
            }
        }
    }
}

// MARK: - TapIn for YouTube (QR)

struct TapInCard: View {
    @State private var url: String?
    @State private var big = false

    var body: some View {
        QCard {
            Text("TapIn · friends listen too").font(.headline).foregroundStyle(Q.text)
            Text("Friends scan this with their phone camera (no app needed) and hear the TV in their own headphones.").font(.footnote).foregroundStyle(Q.textMuted)
            if let u = url {
                HStack(alignment: .center, spacing: 16) {
                    QRCode(text: u).frame(width: 132, height: 132).onTapGesture { big = true }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(u).font(.caption.monospaced()).foregroundStyle(Q.primary).textSelection(.enabled)
                        Text("Same Wi-Fi as this phone. Tap the code to enlarge.").font(.caption).foregroundStyle(Q.textFaint)
                    }
                }
            } else {
                Text("Connect to Wi-Fi to share.").font(.footnote).foregroundStyle(Q.textFaint)
            }
        }
        .onAppear { url = TitleCardServer.shared.tapInURL() }
        .sheet(isPresented: $big) {
            VStack(spacing: 20) {
                Text("Scan to listen").font(.title2.weight(.bold)).foregroundStyle(Q.primary)
                if let u = url { QRCode(text: u).frame(width: 300, height: 300); Text(u).font(.callout.monospaced()).foregroundStyle(Q.textMuted) }
                Button("Done") { big = false }.buttonStyle(GoldButtonStyle())
            }
            .padding(30)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Q.background)
        }
    }
}

struct QRCode: View {
    let text: String

    var body: some View {
        if let img = Self.image(text) {
            Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                .padding(8).background(Color.white, in: RoundedRectangle(cornerRadius: 10))
        } else {
            Color.white.overlay(Text("QR").foregroundStyle(.black))
        }
    }

    static func image(_ s: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(s.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: - Remote

struct RemotePage: View {
    @EnvironmentObject var cast: CastController
    @EnvironmentObject var power: NetworkPower
    @EnvironmentObject var youtube: YouTubeCaster
    @State private var powerMessage: String?

    var body: some View {
        PageScaffold(title: "Remote") {
            powerCard
            if cast.connected {
                QCard {
                    Text(cast.selected?.name ?? "TV").font(.headline).foregroundStyle(Q.text)
                    if let a = cast.activity, a.canControl, !cast.isShowingTitleCard {
                        Text(a.title ?? a.appName).foregroundStyle(Q.primary).lineLimit(2)
                        HStack(spacing: 12) {
                            Button { cast.seek(by: -10) } label: { Image(systemName: "gobackward.10").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                            Button { cast.playPause() } label: { Image(systemName: a.isPlaying ? "pause.fill" : "play.fill").font(.title2).frame(maxWidth: .infinity) }.buttonStyle(GoldButtonStyle())
                            Button { cast.seek(by: 10) } label: { Image(systemName: "goforward.10").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                        }
                        Button { cast.stopCasting() } label: { Label("Stop casting", systemImage: "stop.fill").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                    } else {
                        Text("Nothing playing to control.").foregroundStyle(Q.textMuted)
                    }
                    Button { cast.showTitleCard() } label: { Label("Show the title card", systemImage: "sparkles.tv").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                }
                VolumeCard()
            }
            if let tv = youtube.loungeTV { SamsungRemote(tv: tv) }
        }
    }

    private var powerCard: some View {
        QCard {
            Text("Projector & receiver").font(.headline).foregroundStyle(Q.text)
            HStack(spacing: 12) {
                Button { turn(on: true) } label: { Label("Turn on", systemImage: "power").frame(maxWidth: .infinity) }.buttonStyle(GoldButtonStyle())
                Button { turn(on: false) } label: { Label("Turn off", systemImage: "moon.zzz").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
            }
            if let m = powerMessage { Text(m).font(.footnote).foregroundStyle(Q.primary) }
            Group {
                if power.searching { Text("Looking for your projector and receiver on the Wi-Fi…") }
                else if !power.labels.isEmpty { Text("Controls: " + power.labels.joined(separator: ", ")) }
                else { Text("None found on the Wi-Fi yet. In their own menus, switch on network control (Network Standby, Network Control, PJLink or IP Control), then search again.") }
            }.font(.footnote).foregroundStyle(Q.textMuted)
            Text("On also wakes them through the Chromecast (HDMI-CEC). On and Off work over the Wi-Fi with PJLink projectors and Denon/Marantz, Onkyo/Pioneer/Integra and Yamaha receivers.")
                .font(.footnote).foregroundStyle(Q.textFaint)
            if !power.searching { Button("Search again") { power.search(force: true) }.buttonStyle(GhostButtonStyle()) }
        }
    }

    private func turn(on: Bool) {
        if on { cast.showTitleCard() }
        Task {
            let found = !power.devices.isEmpty
            let ok = found ? await power.setPower(on: on) : false
            await MainActor.run {
                if on { powerMessage = "Turning on the projector and receiver…" }
                else if !found { powerMessage = "No projector or receiver that can be switched off was found on the Wi-Fi. Switch on network control in their menus, then search again." }
                else { powerMessage = ok ? "Projector and receiver going to standby." : "They didn't answer. Check they're on the same Wi-Fi with network control on." }
            }
        }
    }
}

struct SamsungRemote: View {
    let tv: SamsungTV
    @EnvironmentObject var youtube: YouTubeCaster
    @State private var volume = 0.3

    var body: some View {
        QCard {
            Text("\(tv.name) · YouTube").font(.headline).foregroundStyle(Q.text)
            if let p = youtube.samsungNow { Text(clock(p)).font(.caption.monospacedDigit()).foregroundStyle(Q.textMuted) }
            HStack(spacing: 10) {
                Button { youtube.previous() } label: { Image(systemName: "backward.end.fill").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                Button { youtube.seek(by: -10) } label: { Image(systemName: "gobackward.10").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                Button { youtube.playPause() } label: { Image(systemName: youtube.samsungPlaying ? "pause.fill" : "play.fill").frame(maxWidth: .infinity) }.buttonStyle(GoldButtonStyle())
                Button { youtube.seek(by: 10) } label: { Image(systemName: "goforward.10").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                Button { youtube.next() } label: { Image(systemName: "forward.end.fill").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
            }
            HStack {
                Image(systemName: "speaker.fill").foregroundStyle(Q.textMuted)
                Slider(value: $volume, in: 0...1, onEditingChanged: { editing in if !editing { youtube.setVolume(volume) } }).tint(Q.primary)
                Image(systemName: "speaker.wave.3.fill").foregroundStyle(Q.textMuted)
            }
        }
    }
}

// MARK: - YouTube

struct YouTubePage: View {
    @EnvironmentObject var cast: CastController
    @EnvironmentObject var samsung: SamsungFinder
    @EnvironmentObject var youtube: YouTubeCaster
    @EnvironmentObject var headphones: HeadphonesPlayer
    @State private var link = ""

    private struct Row: Identifiable { let id: YouTubeCaster.Target; let name: String }

    private var targets: [Row] {
        var t: [Row] = []
        if let tv = cast.selected, cast.connected { t.append(Row(id: .chromecast(tv.id), name: tv.name)) }
        for s in samsung.tvs { t.append(Row(id: .samsung(s), name: s.name)) }
        // Not on this Wi-Fi now (e.g. at work): YouTube over the internet to TVs cast to before.
        let here = Set(t.map { $0.name.lowercased() })
        for name in YouTubeCaster.awayTVs.keys.sorted() where !here.contains(name.lowercased()) {
            t.append(Row(id: .away(name), name: name + " · away, over the internet"))
        }
        return t
    }

    var body: some View {
        PageScaffold(title: "YouTube") {
            QCard {
                Text("Cast a YouTube video").font(.title3.weight(.bold)).foregroundStyle(Q.text)
                Text("In the YouTube app tap Share → Copy link, then paste it here.").font(.footnote).foregroundStyle(Q.textMuted)
                HStack {
                    TextField("YouTube link", text: $link)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(12).background(Q.backgroundAlt, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Q.borderAccent, lineWidth: 1))
                    Button { if let s = UIPasteboard.general.string { link = s } } label: { Image(systemName: "doc.on.clipboard") }
                        .buttonStyle(GhostButtonStyle()).accessibilityLabel("Paste")
                }
                if let id = YouTubeShared.videoId(link) {
                    AsyncImage(url: URL(string: "https://i.ytimg.com/vi/\(id)/hqdefault.jpg")) { $0.resizable().scaledToFill() } placeholder: { Q.backgroundAlt }
                        .frame(maxWidth: .infinity).aspectRatio(16 / 9, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                Text("TV").font(.subheadline.weight(.bold)).foregroundStyle(Q.text)
                if targets.isEmpty {
                    Text("No TV yet. Pick your Chromecast on the home page, or add a Samsung TV's address in Settings.").font(.footnote).foregroundStyle(Q.textFaint)
                }
                ForEach(targets) { t in
                    Button { youtube.chosen = t.id } label: {
                        HStack {
                            Image(systemName: (youtube.chosen ?? targets.first?.id) == t.id ? "largecircle.fill.circle" : "circle").foregroundStyle(Q.primary)
                            Text(t.name).foregroundStyle(Q.text)
                            Spacer()
                        }.padding(.vertical, 4)
                    }
                }
                Button {
                    guard let t = youtube.chosen ?? targets.first?.id else { return }
                    Task { await youtube.cast(link: link, to: t, cast: cast) }
                } label: {
                    Label(youtube.busy ? "Casting…" : "Cast to TV", systemImage: "play.tv.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(GoldButtonStyle())
                .disabled(youtube.busy || targets.isEmpty || YouTubeShared.videoId(link) == nil)
                ForEach(Array(youtube.steps.enumerated()), id: \.offset) { _, s in
                    Text(s).font(.footnote).foregroundStyle(s.hasSuffix("✓") ? Q.success : Q.textMuted)
                }
                if let p = youtube.problem { Text(p).font(.footnote.weight(.semibold)).foregroundStyle(.red) }
            }
            if let tv = youtube.loungeTV {
                SamsungRemote(tv: tv)
                if headphones.on, headphones.videoId != nil {
                    HeadphonesWebView(player: headphones).aspectRatio(16 / 9, contentMode: .fit)
                }
                HeadphonesCard()
                TapInCard()
            } else if cast.activity?.appId == CastStatus.appYouTube {
                NavigationLink(value: Page.nowCasting) {
                    Label("Open Now casting", systemImage: "tv").frame(maxWidth: .infinity)
                }.buttonStyle(GhostButtonStyle())
            }
        }
        .onAppear { samsung.start(); if link.isEmpty, let s = UIPasteboard.general.string, YouTubeShared.videoId(s) != nil, s.contains("you") { link = s } }
    }
}

// MARK: - Settings

struct SettingsPage: View {
    @EnvironmentObject var updates: UpdateCheck
    @EnvironmentObject var cast: CastController
    @EnvironmentObject var samsung: SamsungFinder
    @State private var samsungIp = SamsungFinder.manualIP ?? ""

    var body: some View {
        PageScaffold(title: "Settings") {
            QCard {
                Text("About").font(.headline).foregroundStyle(Q.text)
                Text("TheatreSync \(updates.currentVersion) (\(updates.currentBuild))").foregroundStyle(Q.textMuted)
                if let u = updates.available {
                    Text("Update available · \(u.version)").font(.subheadline.weight(.bold)).foregroundStyle(Q.primary)
                    if let n = u.notes { Text(n).font(.footnote).foregroundStyle(Q.textMuted) }
                    if let z = u.zipUrl, let url = URL(string: z) {
                        Link(destination: url) { Label("Download the update", systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity) }.buttonStyle(GoldButtonStyle())
                    }
                    Text("Unzip it in Files and replace TheatreSync in Playgrounds.").font(.footnote).foregroundStyle(Q.textFaint)
                } else if updates.checked {
                    Text("Up to date.").font(.footnote).foregroundStyle(Q.success)
                }
                Button("Check for updates") { updates.check() }.buttonStyle(GhostButtonStyle())
            }
            QCard {
                Text("Samsung TV (The Frame)").font(.headline).foregroundStyle(Q.text)
                Text("Found by itself on the Wi-Fi when it's on. If it isn't listed, type its address (TV: Settings → General → Network → Network Status → IP settings).").font(.footnote).foregroundStyle(Q.textMuted)
                HStack {
                    TextField("e.g. 192.168.1.40", text: $samsungIp)
                        .keyboardType(.decimalPad)
                        .padding(12).background(Q.backgroundAlt, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Q.borderAccent, lineWidth: 1))
                    Button("Save") { SamsungFinder.manualIP = samsungIp.trimmingCharacters(in: .whitespaces); samsung.publish() }.buttonStyle(GoldButtonStyle())
                }
                ForEach(samsung.tvs) { tv in Text("• \(tv.name)\(tv.ip.map { " · \($0)" } ?? "")").font(.footnote).foregroundStyle(Q.textMuted) }
            }
            QCard {
                Text("More").font(.headline).foregroundStyle(Q.text)
                NavigationLink(value: Page.headphones) { Label("Headphones", systemImage: "headphones").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                NavigationLink(value: Page.experimental) { Label("Experimental (intro, screensaver)", systemImage: "sparkles").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                NavigationLink(value: Page.mirror) { Label("Show your phone on the TV", systemImage: "rectangle.on.rectangle").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
            }
            QCard {
                Text("Not on iPhone / iPad").font(.headline).foregroundStyle(Q.text)
                Text("NFC TapIn (Apple keeps NFC sharing for its own apps; the QR code works instead), several headphones at once, and installing updates by itself.").font(.footnote).foregroundStyle(Q.textMuted)
            }
        }
    }
}
