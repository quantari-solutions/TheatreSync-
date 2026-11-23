import Foundation
import Network
import SwiftUI

/// Finds Chromecasts on the Wi-Fi (Bonjour `_googlecast._tcp`) and keeps one connection to the chosen TV:
/// what it's showing (title, picture, play state, volume) and its controls. Same protocol as the Android
/// app (Cast v2 over TLS port 8009); no Google library needed.
final class CastController: ObservableObject {
    struct TV: Identifiable, Hashable {
        let id: String          // Bonjour service name
        let name: String        // friendly name ("Media Room TV")
        let endpoint: NWEndpoint
    }

    @Published private(set) var tvs: [TV] = []
    @Published private(set) var selected: TV?
    @Published private(set) var connected = false
    @Published private(set) var activity: CastActivity?
    @Published private(set) var volume: Double = 0.5
    @Published private(set) var muted = false
    @Published var message: String?
    /// When the latest play position arrived (to work out where the TV is now).
    private(set) var activityAt = Date()
    /// YouTube's screen ID from the TV's YouTube app (for casting a link).
    private var mdxScreenId: String?

    private var browser: NWBrowser?
    private var link: CastLink?
    private var requestId = 1
    private var transportConnected: String?
    private var poll: Timer?
    /// What to LOAD once Google's player is open on the TV (title card picture or a movie).
    private var pendingLoad: ((Int) -> String)?
    private let sender = "sender-ts-ios"

    // MARK: - Finding TVs

    func startBrowsing() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_googlecast._tcp", domain: nil), using: NWParameters())
        b.browseResultsChangedHandler = { [weak self] results, _ in
            var found: [TV] = []
            for r in results {
                guard case let .service(name, _, _, _) = r.endpoint else { continue }
                var friendly = name
                if case let .bonjour(txt) = r.metadata, let fn = txt["fn"], !fn.isEmpty { friendly = fn }
                found.append(TV(id: name, name: friendly, endpoint: r.endpoint))
            }
            found.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            Task { @MainActor in self?.tvsFound(found) }
        }
        b.start(queue: .main)
        browser = b
    }

    private func tvsFound(_ found: [TV]) {
        tvs = found
        // Reconnect to the TV used last time as soon as it shows up.
        if selected == nil, let last = UserDefaults.standard.string(forKey: "lastTv"), let tv = found.first(where: { $0.id == last }) {
            select(tv)
        }
    }

    // MARK: - Connection

    func select(_ tv: TV) {
        disconnect()
        selected = tv
        UserDefaults.standard.set(tv.id, forKey: "lastTv")
        let l = CastLink(endpoint: tv.endpoint)
        l.onReady = { [weak self] in Task { @MainActor in self?.linkReady() } }
        l.onMessage = { [weak self] m in Task { @MainActor in self?.handle(m) } }
        l.onClosed = { [weak self] in Task { @MainActor in self?.linkClosed() } }
        link = l
        l.start()
    }

    func disconnect() {
        poll?.invalidate(); poll = nil
        link?.close(); link = nil
        connected = false; activity = nil; transportConnected = nil
    }

    private func nextId() -> Int { requestId += 1; return requestId }

    private func send(_ ns: String, to dest: String = "receiver-0", _ payload: String) {
        link?.send(CastMessage(sourceId: sender, destinationId: dest, namespace: ns, payload: payload))
    }

    private func linkReady() {
        connected = true
        send(CastMessage.nsConnection, "{\"type\":\"CONNECT\"}")
        refresh()
        poll?.invalidate()
        poll = Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in Task { @MainActor in self?.refresh() } }
    }

    private func linkClosed() {
        connected = false
        guard let tv = selected else { return }
        // Try again shortly (TV restarted, Wi-Fi blip).
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, self.selected == tv, !self.connected else { return }
            self.select(tv)
        }
    }

    func refresh() {
        send(CastMessage.nsReceiver, "{\"type\":\"GET_STATUS\",\"requestId\":\(nextId())}")
        if let t = activity?.transportId, activity?.hasMedia == true {
            if transportConnected != t { send(CastMessage.nsConnection, to: t, "{\"type\":\"CONNECT\"}"); transportConnected = t }
            send(CastMessage.nsMedia, to: t, "{\"type\":\"GET_STATUS\",\"requestId\":\(nextId())}")
        }
    }

    private func handle(_ m: CastMessage) {
        switch m.namespace {
        case CastMessage.nsHeartbeat:
            if CastStatus.type(m.payload) == "PING" { send(CastMessage.nsHeartbeat, to: m.sourceId, "{\"type\":\"PONG\"}") }
        case CastMessage.nsReceiver:
            guard CastStatus.type(m.payload) == "RECEIVER_STATUS" else { return }
            let v = CastStatus.volume(m.payload)
            if let l = v.level { volume = l }
            if let mu = v.muted { muted = mu }
            if var a = CastStatus.parseReceiver(m.payload) {
                if let old = activity, old.sessionId == a.sessionId {   // keep media details between polls
                    a.title = old.title; a.subtitle = old.subtitle; a.imageUrl = old.imageUrl; a.playerState = old.playerState
                    a.currentTimeSec = old.currentTimeSec; a.durationSec = old.durationSec; a.mediaSessionId = old.mediaSessionId; a.contentId = old.contentId
                }
                activity = a
                if a.appId == CastStatus.appDefaultMediaReceiver, let t = a.transportId, let load = pendingLoad {
                    pendingLoad = nil
                    send(CastMessage.nsConnection, to: t, "{\"type\":\"CONNECT\"}"); transportConnected = t
                    send(CastMessage.nsMedia, to: t, load(nextId()))
                }
                if a.hasMedia, transportConnected != a.transportId { refresh() }
            } else {
                activity = nil
            }
        case CastMessage.nsMedia:
            guard CastStatus.type(m.payload) == "MEDIA_STATUS", let a = activity else { return }
            activity = CastStatus.mergeMedia(a, m.payload)
            activityAt = Date()
        case YouTubeShared.nsMdx:
            if let s = YouTubeShared.screenId(fromMdx: m.payload) { mdxScreenId = s }
        default: break
        }
    }

    // MARK: - Controls

    func playPause() {
        guard let a = activity, let s = a.mediaSessionId, let t = a.transportId else { return }
        send(CastMessage.nsMedia, to: t, CastStatus.media(a.isPlaying ? "PAUSE" : "PLAY", sessionId: s, requestId: nextId()))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.refresh() }
    }

    func seek(by seconds: Double) {
        guard let a = activity, let s = a.mediaSessionId, let t = a.transportId else { return }
        let to = max(0, (a.currentTimeSec ?? 0) + seconds)
        send(CastMessage.nsMedia, to: t, CastStatus.media("SEEK", sessionId: s, requestId: nextId(), extra: ",\"currentTime\":\(to)"))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.refresh() }
    }

    func seek(to seconds: Double) {
        guard let a = activity, let s = a.mediaSessionId, let t = a.transportId else { return }
        send(CastMessage.nsMedia, to: t, CastStatus.media("SEEK", sessionId: s, requestId: nextId(), extra: ",\"currentTime\":\(max(0, seconds))"))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.refresh() }
    }

    func setVolume(_ level: Double) { volume = level; send(CastMessage.nsReceiver, CastStatus.setVolume(level, requestId: nextId())) }
    func setMuted(_ m: Bool) { muted = m; send(CastMessage.nsReceiver, CastStatus.setMuted(m, requestId: nextId())) }

    /// Stops what's playing on the TV (it goes back to its idle screen).
    func stopCasting() {
        guard let s = activity?.sessionId else { return }
        send(CastMessage.nsReceiver, CastStatus.stop(sessionId: s, requestId: nextId()))
        activity = nil
    }

    /// Shows the living Signature title card on the TV (also wakes the projector and receiver over HDMI-CEC).
    func showTitleCard() {
        guard connected, let url = TitleCardServer.shared.url() else { message = "Couldn't reach the TV over the Wi-Fi."; return }
        load { CastStatus.loadPicture(url: url, requestId: $0) }
    }

    /// Plays a movie served from this phone in Google's player on the TV.
    func playVideo(url: String, contentType: String, title: String) {
        load { CastStatus.loadVideo(url: url, contentType: contentType, title: title, requestId: $0) }
    }

    private func load(_ payload: @escaping (Int) -> String) {
        if let a = activity, a.appId == CastStatus.appDefaultMediaReceiver, let t = a.transportId {
            send(CastMessage.nsConnection, to: t, "{\"type\":\"CONNECT\"}"); transportConnected = t
            send(CastMessage.nsMedia, to: t, payload(nextId()))
        } else {
            pendingLoad = payload
            send(CastMessage.nsReceiver, CastStatus.launch(CastStatus.appDefaultMediaReceiver, requestId: nextId()))
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.refresh() }
    }

    /// Where the TV is in the movie playing from this phone (seconds, playing).
    func mediaNow(contentPrefix: String) -> (position: Double, playing: Bool)? {
        guard let a = activity, a.contentId?.hasPrefix(contentPrefix) == true, let t = a.currentTimeSec else { return nil }
        let playing = a.playerState == "PLAYING"
        return (t + (playing ? Date().timeIntervalSince(activityAt) : 0), playing)
    }

    // MARK: - YouTube

    /// Opens YouTube on the Chromecast (if it isn't already) and asks it for its screen ID.
    @MainActor
    func youTubeScreenId() async -> String? {
        guard connected else { return nil }
        mdxScreenId = nil
        if activity?.appId != CastStatus.appYouTube {
            send(CastMessage.nsReceiver, CastStatus.launch(CastStatus.appYouTube, requestId: nextId()))
        }
        var asked: String?
        for i in 0..<40 {            // up to 20 s
            if let s = mdxScreenId { return s }
            if let a = activity, a.appId == CastStatus.appYouTube, let t = a.transportId {
                if asked != t || i % 4 == 0 {
                    send(CastMessage.nsConnection, to: t, "{\"type\":\"CONNECT\"}")
                    send(YouTubeShared.nsMdx, to: t, "{\"type\":\"getMdxSessionStatus\"}")
                    asked = t
                }
            } else if i % 2 == 0 { refresh() }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        return mdxScreenId
    }

    /// Where the TV's YouTube video is now, for the phone's headphone player.
    var youTubeNow: (videoId: String, position: Double, playing: Bool)? {
        guard let a = activity, a.appId == CastStatus.appYouTube, let id = a.contentId, let t = a.currentTimeSec else { return nil }
        let playing = a.playerState == "PLAYING"
        return (id, t + (playing ? Date().timeIntervalSince(activityAt) : 0), playing)
    }

    /// Our own title card (screensaver) on the TV isn't "something playing".
    var isShowingTitleCard: Bool { activity?.contentId?.hasSuffix("/card.webp") == true }
}

/// One TLS connection to a Chromecast, reading length-prefixed frames. Chromecasts use a self-signed
/// certificate, so it's accepted (same as every Cast client); only local-network traffic.
final class CastLink {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "cast-link")
    var onReady: () -> Void = {}
    var onMessage: (CastMessage) -> Void = { _ in }
    var onClosed: () -> Void = {}
    private var closed = false

    init(endpoint: NWEndpoint) {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, _, complete in complete(true) }, DispatchQueue.global())
        conn = NWConnection(to: endpoint, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.onReady(); self.readFrame()
            case .failed, .cancelled: self.finish()
            default: break
            }
        }
        conn.start(queue: queue)
    }

    func send(_ m: CastMessage) { conn.send(content: m.frame(), completion: .contentProcessed { _ in }) }

    func close() { closed = true; conn.cancel() }

    private func finish() {
        guard !closed else { return }
        closed = true
        onClosed()
    }

    private func readFrame() {
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] header, _, _, error in
            guard let self else { return }
            guard error == nil, let header, let len = CastMessage.frameLength(header) else { self.finish(); return }
            self.conn.receive(minimumIncompleteLength: len, maximumLength: len) { body, _, _, error in
                guard error == nil, let body, let m = CastMessage.decode(body) else { self.finish(); return }
                self.onMessage(m)
                self.readFrame()
            }
        }
    }
}
