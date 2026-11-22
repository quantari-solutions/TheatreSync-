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
        guard let u = Bundle.module.url(forResource: "signature_card_live", withExtension: "webp"),
              let d = try? Data(contentsOf: u) else { return Data() }
        return d
    }()

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
            let ok = request.contains("/card.webp")
            let body = ok ? self.card : Data()
            var h = ok ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 404 Not Found\r\n"
            h += "Content-Type: \(ok ? "image/webp" : "text/plain")\r\nContent-Length: \(body.count)\r\n"
            h += "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
            var out = Data(h.utf8)
            if !head { out.append(body) }
            c.send(content: out, completion: .contentProcessed { _ in c.cancel() })
        }
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
