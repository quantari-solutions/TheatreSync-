import Foundation
import Network

/// Serves the living Signature title card (animated picture, endless loop) from this iPhone/iPad to the
/// Chromecast over the Wi-Fi: http://<this device>:<port>/card.webp. Pictures get no player bar on the TV.
final class TitleCardServer {
    static let shared = TitleCardServer()
    private var listener: NWListener?
    private var port: UInt16?
    private let queue = DispatchQueue(label: "title-card")
    private lazy var card: Data = {
        guard let u = Bundle.main.url(forResource: "signature_card_live", withExtension: "webp"),
              let d = try? Data(contentsOf: u) else { return Data() }
        return d
    }()

    /// The movie the TV plays from this phone (a file picked in Files), served with byte ranges so the
    /// Chromecast can seek.
    private var movieFile: URL?
    private var movieType = "video/mp4"
    func setMovie(_ url: URL?, contentType: String) { lock.lock(); movieFile = url; movieType = contentType; lock.unlock() }

    /// The movie's address for the TV.
    func movieURL(ext: String) -> String? {
        startIfNeeded()
        guard let p = port, let ip = LocalAddress.wifiIPv4() else { return nil }
        return "http://\(ip):\(p)/movie/\(Int(Date().timeIntervalSince1970)).\(ext)"
    }

    /// TapIn for YouTube: what the TV's YouTube is doing now (set by the app 4×/s while TapIn is shown).
    private var ytState = "{\"videoId\":null}"
    private let lock = NSLock()
    func setYouTubeState(_ json: String) { lock.lock(); ytState = json; lock.unlock() }

    /// The TapIn for YouTube page's address for guests (nil without Wi-Fi).
    func tapInURL() -> String? {
        startIfNeeded()
        guard let p = port, let ip = LocalAddress.wifiIPv4() else { return nil }
        return "http://\(ip):\(p)/yt"
    }

    /// The card's address for the TV, starting the little server if needed (nil if there's no Wi-Fi).
    func url() -> String? {
        startIfNeeded()
        guard let p = port, let ip = LocalAddress.wifiIPv4() else { return nil }
        return "http://\(ip):\(p)/card.webp"
    }

    private func startIfNeeded() {
        guard listener == nil else { return }
        guard let l = try? NWListener(using: .tcp, on: .any) else { return }
        let ready = DispatchSemaphore(value: 0)
        l.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.port = l.port?.rawValue; ready.signal() }
            if case .failed = state { ready.signal() }
        }
        l.newConnectionHandler = { [weak self] c in self?.serve(c) }
        l.start(queue: queue)
        listener = l
        _ = ready.wait(timeout: .now() + 2)
    }

    private func serve(_ c: NWConnection) {
        c.start(queue: queue)
        c.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, _, _ in
            guard let self else { c.cancel(); return }
            let request = data.map { String(decoding: $0, as: UTF8.self) } ?? ""
            let head = request.hasPrefix("HEAD")
            let path = request.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
            if path.hasPrefix("/movie/") { self.serveMovie(c, request: request, head: head); return }
            let body: Data, type: String, ok: Bool
            if path.hasPrefix("/card.webp") { body = self.card; type = "image/webp"; ok = true }
            else if path.hasPrefix("/yt/state") {
                self.lock.lock(); let j = self.ytState; self.lock.unlock()
                body = Data(j.utf8); type = "application/json"; ok = true
            } else if path == "/yt" || path.hasPrefix("/yt?") { body = Data(YouTubeListen.tapInPage.utf8); type = "text/html; charset=utf-8"; ok = true }
            else { body = Data("not found".utf8); type = "text/plain"; ok = false }
            var h = ok ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 404 Not Found\r\n"
            h += "Content-Type: \(type)\r\nContent-Length: \(body.count)\r\n"
            h += "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
            var out = Data(h.utf8)
            if !head { out.append(body) }
            c.send(content: out, completion: .contentProcessed { _ in c.cancel() })
        }
    }
}

extension TitleCardServer {
    fileprivate func serveMovie(_ c: NWConnection, request: String, head: Bool) {
        lock.lock(); let file = movieFile; let type = movieType; lock.unlock()
        guard let file, let fh = try? FileHandle(forReadingFrom: file),
              let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value else {
            c.send(content: Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8), completion: .contentProcessed { _ in c.cancel() })
            return
        }
        let range = HTTPRange.parse(request, size: size)
        let start = range?.start ?? 0, end = range?.end ?? (size - 1)
        var h = range == nil ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes \(start)-\(end)/\(size)\r\n"
        h += "Content-Type: \(type)\r\nContent-Length: \(end - start + 1)\r\nAccept-Ranges: bytes\r\n"
        h += "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        c.send(content: Data(h.utf8), completion: .contentProcessed { _ in })
        guard !head else { c.send(content: nil, isComplete: true, completion: .contentProcessed { _ in c.cancel() }); try? fh.close(); return }
        try? fh.seek(toOffset: UInt64(start))
        var left = end - start + 1
        func pump() {
            guard left > 0 else { try? fh.close(); c.send(content: nil, isComplete: true, completion: .contentProcessed { _ in c.cancel() }); return }
            let n = Int(min(left, 256 * 1024))
            guard let chunk = try? fh.read(upToCount: n), !chunk.isEmpty else { try? fh.close(); c.cancel(); return }
            left -= Int64(chunk.count)
            c.send(content: chunk, completion: .contentProcessed { err in
                if err != nil { try? fh.close(); c.cancel() } else { pump() }
            })
        }
        pump()
    }
}

/// This device's Wi-Fi IPv4 address (en0), for the TV to fetch from.
enum LocalAddress {
    static func wifiIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var fallback: String?
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if name == "en0" { return ip }
            if !ip.hasPrefix("127."), fallback == nil, name.hasPrefix("en") { fallback = ip }
        }
        return fallback
    }

    /// "192.168.1" from "192.168.1.23".
    static func subnetPrefix() -> String? {
        guard let ip = wifiIPv4() else { return nil }
        return ip.split(separator: ".").dropLast().joined(separator: ".")
    }
}
