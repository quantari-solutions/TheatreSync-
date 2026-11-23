import SwiftUI
import WebKit
import AVFoundation

/// "TV sound in your headphones" for YouTube (mirror of YouTubeHeadphones.kt). The TV plays the picture;
/// this plays the same video on the phone in YouTube's official embedded player (on screen, as YouTube
/// requires) with its sound going to the headphones, kept in step with the TV 4×/s.
@MainActor
final class HeadphonesPlayer: NSObject, ObservableObject, WKScriptMessageHandler {
    @Published private(set) var on = false
    @Published private(set) var status = "Starting…"
    @Published private(set) var videoId: String?
    private(set) var web: WKWebView?
    private var timer: Timer?
    /// Where the TV is now: (video, seconds, playing).
    var target: @MainActor () -> (videoId: String, position: Double, playing: Bool)? = { nil }

    func setOn(_ v: Bool) {
        on = v
        if v { start() } else { stop() }
    }

    private func start() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        status = "Waiting for the TV…"
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func stop() {
        timer?.invalidate(); timer = nil
        web?.stopLoading()
        web?.configuration.userContentController.removeScriptMessageHandler(forName: "TS")
        web = nil; videoId = nil
    }

    private func tick() {
        guard on, let t = target() else { return }
        if web == nil || videoId == nil { load(t.videoId) }
        web?.evaluateJavaScript(YouTubeListen.steer(videoId: t.videoId, position: t.position, playing: t.playing), completionHandler: nil)
    }

    private func load(_ id: String) {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.userContentController.add(self, name: "TS")
        let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 180), configuration: config)
        w.isOpaque = false
        w.backgroundColor = .clear
        w.scrollView.isScrollEnabled = false
        w.loadHTMLString(YouTubeListen.headphonesPage(videoId: id), baseURL: URL(string: YouTubeListen.origin + "/"))
        web = w
        videoId = id
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let parts = message.body as? [String], parts.count == 2 else { return }
        let drift = Int(parts[0]), problem = parts[1]
        if !problem.isEmpty { status = "YouTube won't play this video outside its own apps (\(problem))." }
        else if let d = drift { status = "In step with the TV · \(d >= 0 ? "+" : "")\(d) ms" }
        else { status = "Ready" }
    }
}

/// Shows the shared player (moving it between pages never reloads it, so the sound doesn't restart).
struct HeadphonesWebView: UIViewRepresentable {
    @ObservedObject var player: HeadphonesPlayer

    func makeUIView(context: Context) -> UIView {
        let box = UIView()
        box.backgroundColor = .black
        return box
    }

    func updateUIView(_ box: UIView, context: Context) {
        guard let w = player.web else { box.subviews.forEach { $0.removeFromSuperview() }; return }
        if w.superview !== box {
            w.removeFromSuperview()
            w.frame = box.bounds
            w.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            box.addSubview(w)
        }
    }
}
