import SwiftUI
import AVFoundation
import UniformTypeIdentifiers

/// Movie night from this iPhone/iPad (like Android's Movie page): the picture goes to the TV through the
/// Chromecast, and the sound plays here, in your headphones, kept in step with the TV.
@MainActor
final class MovieCaster: ObservableObject {
    @Published private(set) var playing: String?
    @Published private(set) var status = ""
    @Published private(set) var driftMs: Int?
    @Published var headphonesOn = true
    private var file: URL?
    private var player: AVPlayer?
    private var timer: Timer?
    private var lastSeek = Date.distantPast
    private var urlPrefix = ""

    func start(_ url: URL, cast: CastController) async {
        stop(cast: nil)
        guard cast.connected else { status = "Pick your TV on the home page first."; return }
        _ = url.startAccessingSecurityScopedResource()
        file = url
        let ext = url.pathExtension.lowercased()
        let type = UTType(filenameExtension: ext)?.preferredMIMEType ?? "video/mp4"
        TitleCardServer.shared.setMovie(url, contentType: type)
        guard let address = TitleCardServer.shared.movieURL(ext: ext.isEmpty ? "mp4" : ext) else { status = "Connect to Wi-Fi first."; return }
        urlPrefix = address.components(separatedBy: "/movie/").first.map { $0 + "/movie/" } ?? address
        let title = url.deletingPathExtension().lastPathComponent
        playing = title
        if UserDefaults.standard.object(forKey: "signatureIntro") as? Bool ?? true {
            status = "Signature intro…"
            cast.showTitleCard()
            try? await Task.sleep(nanoseconds: 4_500_000_000)
        }
        status = "Starting on the TV…"
        cast.playVideo(url: address, contentType: type, title: title)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        let p = AVPlayer(url: url)
        p.automaticallyWaitsToMinimizeStalling = false
        player = p
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self, weak cast] _ in
            Task { @MainActor in if let cast { self?.follow(cast) } }
        }
    }

    func stop(cast: CastController?) {
        timer?.invalidate(); timer = nil
        player?.pause(); player = nil
        if let cast, playing != nil { cast.stopCasting() }
        file?.stopAccessingSecurityScopedResource(); file = nil
        TitleCardServer.shared.setMovie(nil, contentType: "video/mp4")
        playing = nil; driftMs = nil; status = ""
    }

    /// Keeps the headphone sound with the TV: follows play/pause, and jumps when it drifts > 0.25 s.
    private func follow(_ cast: CastController) {
        guard let p = player else { return }
        guard headphonesOn, let tv = cast.mediaNow(contentPrefix: urlPrefix) else {
            if p.rate != 0 { p.pause() }
            if !headphonesOn { status = "Headphone sound off" }
            return
        }
        // Play the sound earlier by the headphones' own delay (Bluetooth), as iOS reports it.
        let target = tv.position + AVAudioSession.sharedInstance().outputLatency
        if !tv.playing {
            if p.rate != 0 { p.pause() }
            status = "Paused on the TV"
            return
        }
        let local = p.currentTime().seconds
        let drift = local.isFinite ? local - target : 99
        if p.rate == 0 || (abs(drift) > 0.25 && Date().timeIntervalSince(lastSeek) > 3) {
            p.seek(to: CMTime(seconds: max(0, target + 0.15), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            p.play()
            lastSeek = Date()
            status = "Syncing…"
            return
        }
        driftMs = Int((drift * 1000).rounded())
        status = "In your headphones · in step with the TV"
    }
}

struct MoviePage: View {
    @EnvironmentObject var cast: CastController
    @EnvironmentObject var movie: MovieCaster
    @State private var picking = false

    var body: some View {
        PageScaffold(title: "Movie night") {
            QCard(highlighted: movie.playing != nil) {
                Text("Play a movie").font(.title3.weight(.bold)).foregroundStyle(Q.text)
                Text("Pick a movie from Files (MP4, M4V or MOV). The picture plays on the TV, the sound in your headphones, in step.").font(.footnote).foregroundStyle(Q.textMuted)
                if !cast.connected {
                    Text("First pick your TV on the home page.").font(.footnote.weight(.semibold)).foregroundStyle(Q.primary)
                }
                Button { picking = true } label: { Label(movie.playing == nil ? "Choose a movie" : "Choose another movie", systemImage: "film").frame(maxWidth: .infinity) }
                    .buttonStyle(GoldButtonStyle()).disabled(!cast.connected)
                if let t = movie.playing {
                    Text(t).font(.headline).foregroundStyle(Q.primary).lineLimit(2)
                    Text(movie.status).font(.footnote).foregroundStyle(Q.textMuted)
                    if let d = movie.driftMs { Text("Headphones vs TV: \(d >= 0 ? "+" : "")\(d) ms").font(.caption.monospacedDigit()).foregroundStyle(Q.textFaint) }
                    Toggle("Sound in my headphones", isOn: $movie.headphonesOn).tint(Q.primary).foregroundStyle(Q.text)
                    Button { movie.stop(cast: cast) } label: { Label("Stop the movie", systemImage: "stop.fill").frame(maxWidth: .infinity) }.buttonStyle(GhostButtonStyle())
                } else if !movie.status.isEmpty {
                    Text(movie.status).font(.footnote).foregroundStyle(Q.primary)
                }
            }
            if movie.playing != nil {
                NavigationLink(value: Page.nowCasting) { Label("Controls (Now casting)", systemImage: "tv").frame(maxWidth: .infinity) }
                    .buttonStyle(GhostButtonStyle())
            }
            QCard {
                Text("Good to know").font(.headline).foregroundStyle(Q.text)
                Text("Keep TheatreSync open during the movie and the phone on the same Wi-Fi as the TV. Pause and skip from Now casting or the remote; the headphones follow.").font(.footnote).foregroundStyle(Q.textMuted)
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.movie, .mpeg4Movie, .quickTimeMovie]) { result in
            if case .success(let url) = result { Task { await movie.start(url, cast: cast) } }
        }
    }
}
