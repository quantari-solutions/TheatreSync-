import SwiftUI

/// The iPhone / iPad home: your TV, what's playing on it with its controls (Now casting + remote),
/// projector & receiver on / off, the living title card and screensaver, listen along, and About.
struct HomeView: View {
    @ObservedObject var cast: CastController
    @ObservedObject var power: NetworkPower
    @ObservedObject var updates: UpdateCheck
    @ObservedObject var session: ListenerSession
    @ObservedObject var browser: HostBrowser
    @AppStorage("screensaver") private var screensaver = true
    @State private var powerMessage: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Eyebrow(text: "Quantari · TheatreSync")
                GradientTitle(text: "TheatreSync")
                tvCard
                if cast.connected { nowCasting; volumeCard }
                powerCard
                if cast.connected { titleCardCard }
                NavigationLink { JoinView(session: session, browser: browser) } label: {
                    Label("Listen along in headphones", systemImage: "headphones").frame(maxWidth: .infinity)
                }
                .buttonStyle(GhostButtonStyle())
                aboutCard
                Text("TheatreSync · by Quantari Solutions").font(.caption2).foregroundStyle(Q.textFaint).frame(maxWidth: .infinity)
            }
            .padding(20)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(GridBackground())
        .refreshable { cast.refresh(); power.search(force: true); updates.check() }
        .onAppear { cast.startBrowsing(); power.search(); updates.check() }
        .onChange(of: cast.connected) { _ in maybeScreensaver() }
        .onChange(of: cast.activity) { _ in maybeScreensaver() }
    }

    /// Connected with nothing playing: the TV shows the living title card (when the screensaver is on).
    private func maybeScreensaver() {
        guard screensaver, cast.connected, cast.activity == nil else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            if screensaver, cast.connected, cast.activity == nil { cast.showTitleCard() }
        }
    }

    // MARK: - Cards

    private var tvCard: some View {
        QCard(highlighted: cast.connected) {
            Text("Your TV").font(.title3.weight(.bold)).foregroundStyle(Q.text)
            if cast.tvs.isEmpty {
                StatusPill(text: "Looking for Chromecasts…", color: Q.primary, pulsing: true)
                Text("Same Wi-Fi as the Chromecast, and allow Local Network when iOS asks.").font(.footnote).foregroundStyle(Q.textMuted)
            }
            ForEach(cast.tvs) { tv in
                Button { cast.select(tv) } label: {
                    HStack {
                        Image(systemName: "tv").foregroundStyle(Q.primary)
                        Text(tv.name).foregroundStyle(Q.text).lineLimit(1)
                        Spacer()
                        if cast.selected == tv {
                            StatusPill(text: cast.connected ? "Connected" : "Connecting…", color: cast.connected ? Q.success : Q.primary, pulsing: !cast.connected)
                        }
                    }
                    .padding(.vertical, 6)
                }
                .accessibilityHint("Connects to \(tv.name)")
            }
        }
    }

    private var nowCasting: some View {
        QCard(highlighted: cast.activity?.isPlaying == true) {
            if let a = cast.activity, !cast.isShowingTitleCard {
                if let img = a.imageUrl, let u = URL(string: img) {
                    AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { Q.backgroundAlt }
                        .frame(maxWidth: .infinity).aspectRatio(16 / 9, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                Text(a.appName).font(.caption.weight(.bold)).foregroundStyle(Q.success)
                Text(a.title ?? a.appName).font(.title3.weight(.bold)).foregroundStyle(Q.primary).lineLimit(2)
                if let s = a.subtitle { Text(s).font(.subheadline).foregroundStyle(Q.textMuted) }
                if a.canControl {
                    HStack(spacing: 14) {
                        Button("−10s") { cast.seek(by: -10) }.buttonStyle(GhostButtonStyle())
                        Button { cast.playPause() } label: { Image(systemName: a.isPlaying ? "pause.fill" : "play.fill").font(.title2).frame(maxWidth: .infinity) }
                            .buttonStyle(GoldButtonStyle()).accessibilityLabel(a.isPlaying ? "Pause" : "Play")
                        Button("+10s") { cast.seek(by: 10) }.buttonStyle(GhostButtonStyle())
                    }
                }
                Button { cast.stopCasting() } label: { Label("Stop casting", systemImage: "stop.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(GhostButtonStyle())
            } else {
                Text(cast.selected?.name ?? "TV").font(.title3.weight(.bold)).foregroundStyle(Q.text)
                Text(cast.isShowingTitleCard ? "Showing the TheatreSync title card." : "Ready to cast.").foregroundStyle(Q.textMuted)
            }
        }
    }

    private var volumeCard: some View {
        QCard {
            HStack {
                Text("TV volume").font(.headline).foregroundStyle(Q.text)
                Spacer()
                Button(cast.muted ? "Unmute" : "Mute") { cast.setMuted(!cast.muted) }.buttonStyle(GhostButtonStyle())
            }
            Slider(value: Binding(get: { cast.volume }, set: { cast.setVolume($0) }), in: 0...1).tint(Q.primary)
            Text("Controls the Chromecast's volume (room speakers).").font(.footnote).foregroundStyle(Q.textMuted)
        }
    }

    private var powerCard: some View {
        QCard {
            Text("Projector & receiver").font(.headline).foregroundStyle(Q.text)
            HStack(spacing: 12) {
                Button("Turn on") {
                    cast.showTitleCard() // wakes them through the Chromecast (HDMI-CEC)
                    Task { _ = await power.setPower(on: true); await MainActor.run { powerMessage = "Turning on the projector and receiver…" } }
                }.buttonStyle(GoldButtonStyle())
                Button("Turn off") {
                    Task {
                        let ok = power.devices.isEmpty ? false : await power.setPower(on: false)
                        await MainActor.run {
                            powerMessage = power.devices.isEmpty
                                ? "No projector or receiver that can be switched off was found on the Wi-Fi. Switch on network control in their menus (Network Standby / PJLink / IP Control), then search again."
                                : (ok ? "Projector and receiver going to standby." : "They didn't answer. Check they're on the same Wi-Fi with network control on.")
                        }
                    }
                }.buttonStyle(GhostButtonStyle())
            }
            if let m = powerMessage { Text(m).font(.footnote).foregroundStyle(Q.primary) }
            Group {
                if power.searching { Text("Looking for your projector and receiver on the Wi-Fi…") }
                else if !power.labels.isEmpty { Text("Controls: " + power.labels.joined(separator: ", ")) }
                else { Text("None found on the Wi-Fi yet. In their own menus, switch on network control (Network Standby, Network Control, PJLink or IP Control), then search again.") }
            }.font(.footnote).foregroundStyle(Q.textMuted)
            Text("On wakes them through the Chromecast (HDMI-CEC). On and Off also work over the Wi-Fi with PJLink projectors and Denon/Marantz, Onkyo/Pioneer/Integra and Yamaha receivers.")
                .font(.footnote).foregroundStyle(Q.textFaint)
            if !power.searching { Button("Search again") { power.search(force: true) }.buttonStyle(GhostButtonStyle()) }
        }
    }

    private var titleCardCard: some View {
        QCard {
            Toggle(isOn: $screensaver) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("TV screensaver").font(.headline).foregroundStyle(Q.text)
                    Text("The living TheatreSync title card when nothing's playing.").font(.footnote).foregroundStyle(Q.textMuted)
                }
            }.tint(Q.primary)
            Button("Show the title card now") { cast.showTitleCard() }.buttonStyle(GhostButtonStyle())
        }
    }

    private var aboutCard: some View {
        QCard {
            Text("About").font(.headline).foregroundStyle(Q.text)
            Text("TheatreSync \(updates.currentVersion) (\(updates.currentBuild))").foregroundStyle(Q.textMuted)
            if let u = updates.available {
                Text("Update available · \(u.version)").font(.subheadline.weight(.bold)).foregroundStyle(Q.primary)
                if let n = u.notes { Text(n).font(.footnote).foregroundStyle(Q.textMuted) }
                if let z = u.zipUrl, let url = URL(string: z) {
                    Link("Download the update", destination: url).buttonStyle(GoldButtonStyle())
                }
                Text("Unzip it in Files and replace TheatreSync.swiftpm in Playgrounds.").font(.footnote).foregroundStyle(Q.textFaint)
            } else if updates.checked {
                Text("Up to date.").font(.footnote).foregroundStyle(Q.success)
            }
        }
    }
}
