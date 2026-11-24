import SwiftUI
import AVFoundation
import AVKit

// MARK: - Headphones (Android: Headphones + Headphone settings)

struct HeadphonesPage: View {
    @State private var outputs: [String] = []
    @State private var latencyMs = 0
    @State private var speech = AVSpeechSynthesizer()

    var body: some View {
        PageScaffold(title: "Headphones") {
            QCard {
                Text("Sound is going to").font(.headline).foregroundStyle(Q.text)
                ForEach(outputs, id: \.self) { o in Label(o, systemImage: "headphones").foregroundStyle(Q.primary) }
                Text("Their own delay: \(latencyMs) ms. TheatreSync allows for it automatically.").font(.footnote).foregroundStyle(Q.textMuted)
                HStack {
                    Text("Choose where the sound goes").foregroundStyle(Q.text)
                    Spacer()
                    RoutePicker().frame(width: 44, height: 44)
                }
                Text("New headphones: pair them once in iOS Settings → Bluetooth.").font(.footnote).foregroundStyle(Q.textFaint)
            }
            QCard {
                Text("Headphone sound test").font(.headline).foregroundStyle(Q.text)
                Text("Plays a short voice in your headphones.").font(.footnote).foregroundStyle(Q.textMuted)
                Button {
                    try? AVAudioSession.sharedInstance().setCategory(.playback)
                    try? AVAudioSession.sharedInstance().setActive(true)
                    speech.speak(AVSpeechUtterance(string: "TheatreSync sound test. You're all set."))
                } label: { Label("Play the test", systemImage: "speaker.wave.2.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(GoldButtonStyle())
            }
            NavigationLink(value: Page.multiAudio) { Label("Two headsets?", systemImage: "person.2.fill").frame(maxWidth: .infinity) }
                .buttonStyle(GhostButtonStyle())
            NavigationLink { DiagnosticsView() } label: { Label("Headphone and network check", systemImage: "stethoscope").frame(maxWidth: .infinity) }
                .buttonStyle(GhostButtonStyle())
        }
        .onAppear(perform: read)
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification).receive(on: DispatchQueue.main)) { _ in read() }
    }

    private func read() {
        let s = AVAudioSession.sharedInstance()
        outputs = s.currentRoute.outputs.map { $0.portName }
        latencyMs = Int((s.outputLatency * 1000).rounded())
    }
}

/// Apple's own output picker (Bluetooth headphones, AirPlay).
struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = UIColor(red: 0.94, green: 0.77, blue: 0.30, alpha: 1)
        v.activeTintColor = v.tintColor
        return v
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

// MARK: - Two headsets (Android: Multi audio)

struct MultiAudioPage: View {
    var body: some View {
        PageScaffold(title: "Two headsets") {
            QCard {
                Text("AirPods or Beats").font(.headline).foregroundStyle(Q.text)
                Text("iPhone and iPad can share sound with two pairs: connect yours, then Control Center → tap the AirPlay icon on the music tile → Share Audio, and hold the second pair near.").font(.footnote).foregroundStyle(Q.textMuted)
            }
            QCard {
                Text("Any other headphones").font(.headline).foregroundStyle(Q.text)
                Text("iOS plays to one Bluetooth headset at a time. Friends can listen on their own phone instead: TapIn's QR code on Now casting (YouTube), or Listen along to a movie on an Android phone running TheatreSync.").font(.footnote).foregroundStyle(Q.textMuted)
            }
        }
    }
}

// MARK: - Mirror (Android: Show your phone on the TV)

struct MirrorPage: View {
    var body: some View {
        PageScaffold(title: "Show your phone on the TV") {
            QCard {
                Text("To The Frame (Samsung)").font(.headline).foregroundStyle(Q.text)
                Text("Samsung TVs take Apple's AirPlay: open Control Center → Screen Mirroring → pick the TV. The first time, type the code shown on the TV.").font(.footnote).foregroundStyle(Q.textMuted)
                RoutePicker().frame(width: 44, height: 44)
            }
            QCard {
                Text("To the Chromecast").font(.headline).foregroundStyle(Q.text)
                Text("Apple doesn't let iPhone or iPad mirror the whole screen to a Chromecast. Cast instead: YouTube from the YouTube page, a movie from Movie night, or the Cast button inside apps.").font(.footnote).foregroundStyle(Q.textMuted)
            }
            QCard {
                Text("From the laptop").font(.headline).foregroundStyle(Q.text)
                Text("In Chrome: ⋮ menu → Cast… → pick Media Room TV → Sources → Cast tab or Cast screen.").font(.footnote).foregroundStyle(Q.textMuted)
            }
        }
    }
}

// MARK: - Remote cast (Android: Remote cast page)

/// Away from home: YouTube goes to the home TV over YouTube's own link (works now). A movie from this phone
/// and mirroring need something at home to receive them (a Chromecast only takes those from its Wi-Fi).
struct RemoteCastPage: View {
    var body: some View {
        PageScaffold(title: "Remote cast") {
            Text("Away from home, send something to your TV over the internet.").font(.footnote).foregroundStyle(Q.textMuted)
            NavigationLink(value: Page.youtube) { option("play.rectangle.fill", "YouTube", "Paste a link: it plays on the TV at home, with play, pause, skip and stop here.", "Ready", true) }
                .buttonStyle(.plain)
            option("film", "A movie from this phone", "Your own video files on the TV at home.", "Needs the home hub (coming)", false)
            option("rectangle.on.rectangle", "Mirror screen", "Show this phone's screen on the TV at home.", "Needs the home hub (coming)", false)
            QCard {
                Text("Your TVs").font(.headline).foregroundStyle(Q.text)
                ForEach(YouTubeCaster.awayTVs.keys.sorted(), id: \.self) { Text($0).foregroundStyle(Q.text) }
                Text("A Chromecast with Google TV can be the home hub itself; with a classic Chromecast, movies and mirroring from away need your router's VPN.").font(.footnote).foregroundStyle(Q.textMuted)
            }
        }
    }

    private func option(_ icon: String, _ title: String, _ text: String, _ status: String, _ ready: Bool) -> some View {
        QCard(highlighted: ready) {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.title2).foregroundStyle(ready ? Q.primary : Q.textFaint)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(ready ? Q.text : Q.textMuted)
                    Text(text).font(.footnote).foregroundStyle(Q.textMuted)
                }
            }
            StatusPill(text: status, color: ready ? Q.success : Q.textFaint)
        }
    }
}

// MARK: - Experimental (Android: Settings → Experimental)

struct ExperimentalPage: View {
    @EnvironmentObject var cast: CastController
    @AppStorage("screensaver") private var screensaver = true
    @AppStorage("signatureIntro") private var signatureIntro = true
    @AppStorage("remoteCast") private var remoteCast = true

    var body: some View {
        PageScaffold(title: "Experimental") {
            QCard(highlighted: remoteCast) {
                Toggle(isOn: $remoteCast) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Remote cast").foregroundStyle(Q.text)
                        Text("YouTube to your home TV from anywhere. TVs you've cast YouTube to appear as \"away\" on the YouTube page when you're out. The TV must be on; on a Chromecast, YouTube may need to be open there.").font(.footnote).foregroundStyle(Q.textMuted)
                    }
                }.tint(Q.primary)
            }
            QCard {
                Toggle(isOn: $signatureIntro) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Signature intro").foregroundStyle(Q.text)
                        Text("The living title card on the TV before a movie or YouTube video starts.").font(.footnote).foregroundStyle(Q.textMuted)
                    }
                }.tint(Q.primary)
                Toggle(isOn: $screensaver) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("TV screensaver").foregroundStyle(Q.text)
                        Text("The living title card when nothing's playing.").font(.footnote).foregroundStyle(Q.textMuted)
                    }
                }.tint(Q.primary)
                if cast.connected { Button("Show the title card now") { cast.showTitleCard() }.buttonStyle(GhostButtonStyle()) }
            }
        }
    }
}
