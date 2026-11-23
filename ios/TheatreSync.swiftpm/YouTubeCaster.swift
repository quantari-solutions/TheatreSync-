import Foundation
import Network

/// A Samsung TV (The Frame) found on the Wi-Fi. YouTube is cast to it the way the YouTube app does:
/// DIAL starts YouTube on the TV and gives its screen ID, then YouTube's own remote-control service plays.
struct SamsungTV: Identifiable, Hashable {
    let id: String      // Bonjour name, or "ip:<address>" when added by hand
    let name: String
    var ip: String?
}

/// Finds Samsung TVs through their own Wi-Fi announcement (Bonjour `_samsungmsf._tcp`), the one way iOS
/// lets an app find them without a special Apple permission. Also keeps TVs added by address in Settings.
final class SamsungFinder: ObservableObject {
    @Published private(set) var tvs: [SamsungTV] = []
    private var browser: NWBrowser?
    private var found: [String: SamsungTV] = [:]
    private var resolving: [String: NWConnection] = [:]

    static var manualIP: String? {
        get { UserDefaults.standard.string(forKey: "samsungIp").flatMap { $0.isEmpty ? nil : $0 } }
        set { UserDefaults.standard.set(newValue ?? "", forKey: "samsungIp") }
    }

    func start() {
        publish()
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_samsungmsf._tcp", domain: nil), using: NWParameters())
        b.browseResultsChangedHandler = { [weak self] results, _ in
            DispatchQueue.main.async { self?.results(results) }
        }
        b.start(queue: .main)
        browser = b
    }

    private func results(_ results: Set<NWBrowser.Result>) {
        for r in results {
            guard case let .service(name, _, _, _) = r.endpoint else { continue }
            var ip: String?
            if case let .bonjour(txt) = r.metadata, let se = txt["se"] { ip = Self.host(fromURL: se) }
            let friendly = name.replacingOccurrences(of: "[TV] ", with: "")
            if found[name] == nil || found[name]?.ip == nil { found[name] = SamsungTV(id: name, name: friendly, ip: ip) }
            if ip == nil, found[name]?.ip == nil { resolve(name, r.endpoint) }
        }
        publish()
    }

    /// The TV's address: connect once and read where the connection went.
    private func resolve(_ name: String, _ endpoint: NWEndpoint) {
        guard resolving[name] == nil else { return }
        let c = NWConnection(to: endpoint, using: .tcp)
        resolving[name] = c
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                var ip: String?
                if case let .hostPort(host, _)? = c.currentPath?.remoteEndpoint { ip = Self.clean("\(host)") }
                c.cancel()
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.resolving[name] = nil
                    if let ip { self.found[name]?.ip = ip; self.publish() }
                }
            case .failed:
                c.cancel()
                DispatchQueue.main.async { self?.resolving[name] = nil }
            default: break
            }
        }
        c.start(queue: .main)
    }

    func publish() {
        var list = Array(found.values).sorted { $0.name < $1.name }
        if let m = Self.manualIP, !list.contains(where: { $0.ip == m }) {
            list.append(SamsungTV(id: "ip:" + m, name: "Samsung TV (\(m))", ip: m))
        }
        tvs = list
    }

    static func host(fromURL s: String) -> String? {
        guard let u = URL(string: s), let h = u.host, !h.isEmpty else { return nil }
        return clean(h)
    }

    private static func clean(_ h: String) -> String { String(h.split(separator: "%").first ?? Substring(h)) }
}

/// Plain HTTP on the home Wi-Fi (DIAL), straight over a TCP connection so iOS's web-security rules for
/// internet sites don't get in the way of talking to the TV.
enum LanHTTP {
    struct Reply { let status: Int; let body: String }

    static func request(_ method: String, host: String, port: UInt16, path: String, body: String = "", timeout: Double = 6) async -> Reply? {
        guard let p = NWEndpoint.Port(rawValue: port) else { return nil }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
        let req = "\(method) \(path) HTTP/1.1\r\nHost: \(host):\(port)\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
        let once = Gate()
        return await withCheckedContinuation { (k: CheckedContinuation<Reply?, Never>) in
            let buffer = Buffer()
            func finish(_ r: Reply?) { if once.claim() { conn.cancel(); k.resume(returning: r) } }
            func read() {
                conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, done, error in
                    if let data { buffer.data.append(data) }
                    if done || error != nil { finish(parse(buffer.data)) } else { read() }
                }
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    conn.send(content: Data(req.utf8), completion: .contentProcessed { _ in read() })
                case .failed, .cancelled: finish(parse(buffer.data))
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(parse(buffer.data)) }
        }
    }

    private final class Buffer { var data = Data() }
    private final class Gate {
        private var done = false; private let lock = NSLock()
        func claim() -> Bool { lock.lock(); defer { lock.unlock() }; if done { return false }; done = true; return true }
    }

    private static func parse(_ d: Data) -> Reply? {
        let s = String(decoding: d, as: UTF8.self)
        guard s.hasPrefix("HTTP/"), let line = s.split(separator: "\r\n").first else { return nil }
        let parts = line.split(separator: " ")
        guard parts.count >= 2, let code = Int(parts[1]) else { return nil }
        let body = s.components(separatedBy: "\r\n\r\n").dropFirst().joined(separator: "\r\n\r\n")
        return Reply(status: code, body: body)
    }
}

/// YouTube's remote-control service ("Lounge"), the same one the YouTube app uses to cast. Mirror of
/// YouTubeLounge in YouTube.kt.
final class YouTubeLounge {
    struct Session { let token: String; let sid: String; let gsid: String }
    private let base = "https://www.youtube.com/"
    private var rid = 0
    private var reqCount = 0
    private(set) var session: Session?

    func connect(screenId: String) async throws {
        let tokenBody = try await post("api/lounge/pairing/get_lounge_token_batch", [], [("screen_ids", screenId)], nil)
        guard let token = YouTubeShared.loungeToken(fromJson: tokenBody) else { throw Problem("YouTube didn't recognise the TV") }
        rid = 0; reqCount = 0
        let bind = try await post("api/lounge/bc/bind", [("RID", "0"), ("VER", "8"), ("CVER", "1")], [
            ("device", "REMOTE_CONTROL"), ("id", "theatresyncios0000000000"), ("name", "TheatreSync"),
            ("mdx-version", "3"), ("pairing_type", "cast"), ("app", "android-phone-13.14.55"),
            ("capabilities", "que,dsdtr,atp,vsp"),
        ], token)
        guard let sid = YouTubeShared.sid(fromBind: bind), let gsid = YouTubeShared.gsessionId(fromBind: bind) else {
            throw Problem("YouTube on the TV didn't answer")
        }
        session = Session(token: token, sid: sid, gsid: gsid)
    }

    func play(videoId: String) async throws {
        try await command("setPlaylist", [("videoId", videoId), ("currentTime", "0"), ("currentIndex", "-1"), ("audioOnly", "false"), ("listId", "")])
    }

    /// "play", "pause", "seekTo" (newTime), "next", "previous", "setVolume" (volume 0–100).
    func command(_ name: String, _ params: [(String, String)] = []) async throws {
        guard let s = session else { throw Problem("Not connected to YouTube on the TV") }
        rid += 1
        let prefix = "req\(reqCount)"
        var form: [(String, String)] = [("count", "1"), ("\(prefix)__sc", name)]
        for (k, v) in params { form.append(("\(prefix)_\(k)", v)) }
        reqCount += 1
        _ = try await post("api/lounge/bc/bind", [("SID", s.sid), ("gsessionid", s.gsid), ("RID", "\(rid)"), ("VER", "8"), ("CVER", "1")], form, s.token)
    }

    /// What arrived from the TV within a few seconds (state changes: position, playing, video).
    func events(waitSeconds: Double = 4) async -> String {
        guard let s = session else { return "" }
        let q = YouTubeShared.formEncode([("SID", s.sid), ("gsessionid", s.gsid), ("RID", "rpc"), ("VER", "8"), ("CVER", "1"), ("TYPE", "xmlhttp"), ("CI", "0"), ("AID", "0")])
        guard let url = URL(string: base + "api/lounge/bc/bind?" + q) else { return "" }
        var r = URLRequest(url: url); r.timeoutInterval = waitSeconds + 4
        r.setValue(base, forHTTPHeaderField: "Origin"); r.setValue(s.token, forHTTPHeaderField: "X-YouTube-LoungeId-Token")
        let collected = Buffer()
        let reader = Task {
            guard let result = try? await URLSession.shared.bytes(for: r) else { return }
            do { for try await b in result.0 { collected.append(b) } } catch {}
        }
        try? await Task.sleep(nanoseconds: UInt64(waitSeconds * 1_000_000_000))
        reader.cancel()
        return collected.text
    }

    private final class Buffer {
        private var d = Data(); private let lock = NSLock()
        func append(_ b: UInt8) { lock.lock(); d.append(b); lock.unlock() }
        var text: String { lock.lock(); defer { lock.unlock() }; return String(decoding: d, as: UTF8.self) }
    }

    private func post(_ path: String, _ query: [(String, String)], _ form: [(String, String)], _ token: String?) async throws -> String {
        let q = query.isEmpty ? "" : "?" + YouTubeShared.formEncode(query)
        guard let url = URL(string: base + path + q) else { throw Problem("bad address") }
        var r = URLRequest(url: url); r.httpMethod = "POST"; r.timeoutInterval = 12
        r.setValue(base, forHTTPHeaderField: "Origin")
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        if let token { r.setValue(token, forHTTPHeaderField: "X-YouTube-LoungeId-Token") }
        r.httpBody = Data(YouTubeShared.formEncode(form).utf8)
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw Problem("YouTube answered \(code)") }
        return String(decoding: data, as: UTF8.self)
    }
}

struct Problem: Error, LocalizedError {
    let text: String
    init(_ t: String) { text = t }
    var errorDescription: String? { text }
}

/// Casting a YouTube link to the chosen TV, step by step (shown on the YouTube page), then the TV's
/// remote for it. Chromecast: its YouTube app gives the screen ID over the Cast connection. Samsung:
/// DIAL on port 8080 starts YouTube and gives it.
@MainActor
final class YouTubeCaster: ObservableObject {
    enum Target: Hashable { case chromecast(String), samsung(SamsungTV) }

    @Published private(set) var steps: [String] = []
    @Published private(set) var busy = false
    @Published private(set) var problem: String?
    @Published private(set) var castVideoId: String?
    @Published private(set) var target: Target?
    /// The TV's YouTube when it isn't a Chromecast (from YouTube's events): position, playing.
    @Published private(set) var samsungPosition: Double?
    @Published private(set) var samsungPlaying = false
    private var samsungAt = Date()
    private let lounge = YouTubeLounge()
    private var listening: Task<Void, Never>?

    func cast(link: String, to target: Target, cast: CastController) async {
        problem = nil; steps = []
        guard let id = YouTubeShared.videoId(link) else { problem = "That doesn't look like a YouTube link."; return }
        busy = true
        defer { busy = false }
        do {
            let screenId: String
            switch target {
            case .chromecast:
                // Signature intro: the living title card first (also wakes the projector and receiver).
                if UserDefaults.standard.object(forKey: "signatureIntro") as? Bool ?? true, cast.activity?.appId != CastStatus.appYouTube {
                    step("Signature intro…")
                    cast.showTitleCard()
                    try? await Task.sleep(nanoseconds: 4_500_000_000)
                }
                step("Opening YouTube on the TV…")
                guard let s = await cast.youTubeScreenId() else { throw Problem("YouTube on the Chromecast didn't answer. Try again in a moment.") }
                screenId = s
            case .samsung(let tv):
                guard let ip = tv.ip else { throw Problem("Still finding \(tv.name)'s address on the Wi-Fi. Try again in a few seconds.") }
                step("Opening YouTube on \(tv.name)…")
                screenId = try await Self.samsungScreenId(ip: ip)
            }
            step("Connecting to YouTube…")
            try await lounge.connect(screenId: screenId)
            step("Playing on the TV…")
            try await lounge.play(videoId: id)
            castVideoId = id
            self.target = target
            step("Playing ✓")
            if case .samsung = target { listen() } else { listening?.cancel(); samsungPosition = nil }
        } catch {
            problem = (error as? LocalizedError)?.errorDescription ?? "Couldn't reach the TV."
        }
    }

    private func step(_ s: String) { steps.append(s) }

    // MARK: Remote for a Samsung TV (a Chromecast uses its own controls)

    func playPause() { let p = samsungPlaying; samsungPlaying = !p; send(p ? "pause" : "play") }
    func seek(by s: Double) { let to = max(0, (samsungNow ?? 0) + s); send("seekTo", [("newTime", String(format: "%.1f", to))]) }
    func next() { send("next") }
    func previous() { send("previous") }
    func setVolume(_ v: Double) { send("setVolume", [("volume", "\(Int(v * 100))")]) }

    private func send(_ name: String, _ p: [(String, String)] = []) {
        Task { try? await lounge.command(name, p) }
    }

    var samsungNow: Double? {
        guard let p = samsungPosition else { return nil }
        return p + (samsungPlaying ? Date().timeIntervalSince(samsungAt) : 0)
    }

    /// Follows the TV's YouTube (position, play state, video) for Now casting and the headphones.
    private func listen() {
        listening?.cancel()
        listening = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let ev = await self.lounge.events(waitSeconds: 3)
                if let t = Self.last("\"currentTime\"\\s*:\\s*\"([0-9.]+)\"", ev).flatMap(Double.init) { self.samsungPosition = t; self.samsungAt = Date() }
                if let st = Self.last("\"state\"\\s*:\\s*\"(-?\\d+)\"", ev) { self.samsungPlaying = st == "1" }
                if let v = Self.last("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"", ev) { self.castVideoId = v }
            }
        }
    }

    private static func last(_ pattern: String, _ s: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        guard let m = re.matches(in: s, range: NSRange(s.startIndex..., in: s)).last, let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    /// DIAL: YouTube's screen ID from the TV, starting YouTube first if needed (waits up to 20 s).
    static func samsungScreenId(ip: String) async throws -> String {
        let path = "/ws/apps/YouTube"
        if let r = await LanHTTP.request("GET", host: ip, port: 8080, path: path), let s = YouTubeShared.dialTag(r.body, "screenId") { return s }
        guard let launch = await LanHTTP.request("POST", host: ip, port: 8080, path: path) else {
            throw Problem("The TV didn't answer. Check it's on (or in Art mode) and on the same Wi-Fi.")
        }
        guard (200..<300).contains(launch.status) else { throw Problem("The TV refused to open YouTube (\(launch.status)).") }
        for _ in 0..<20 {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if let r = await LanHTTP.request("GET", host: ip, port: 8080, path: path), let s = YouTubeShared.dialTag(r.body, "screenId") { return s }
        }
        throw Problem("YouTube opened on the TV but didn't get ready. Try again.")
    }
}
