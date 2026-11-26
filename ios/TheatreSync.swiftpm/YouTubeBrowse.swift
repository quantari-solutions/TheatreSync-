import SwiftUI
import WebKit

/// "Find a video": YouTube's mobile website inside TheatreSync (no YouTube app needed for a link). Tap a
/// video and a "Cast this video" button appears; it goes back to the YouTube page and casts it.
struct YouTubeBrowsePage: View {
    @EnvironmentObject var youtube: YouTubeCaster
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = BrowseModel()

    var body: some View {
        ZStack(alignment: .bottom) {
            BrowseView(model: model).ignoresSafeArea(edges: .bottom)
            if YouTubeShared.videoId(model.url) != nil {
                Button {
                    model.web?.evaluateJavaScript("document.querySelectorAll('video').forEach(v=>v.pause())", completionHandler: nil)
                    youtube.pickedLink = model.url
                    dismiss()
                } label: { Label("Cast this video", systemImage: "play.tv.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(GoldButtonStyle())
                    .padding(16)
            }
        }
        .background(Q.background)
        .navigationTitle("Find a video")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Q.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }
}

final class BrowseModel: NSObject, ObservableObject {
    @Published var url = "https://m.youtube.com/"
    weak var web: WKWebView?
    private var watch: NSKeyValueObservation?

    func attach(_ w: WKWebView) {
        web = w
        // YouTube's site changes page without loading a new one: follow its address.
        watch = w.observe(\.url, options: [.new]) { [weak self] w, _ in
            let u = w.url?.absoluteString ?? ""
            DispatchQueue.main.async { self?.url = u }
        }
    }
}

struct BrowseView: UIViewRepresentable {
    let model: BrowseModel

    func makeUIView(context: Context) -> WKWebView {
        let c = WKWebViewConfiguration()
        c.allowsInlineMediaPlayback = true
        c.mediaTypesRequiringUserActionForPlayback = .all // the TV plays it, not the phone
        let w = WKWebView(frame: .zero, configuration: c)
        w.allowsBackForwardNavigationGestures = true
        model.attach(w)
        if let u = URL(string: "https://m.youtube.com/") { w.load(URLRequest(url: u)) }
        return w
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
