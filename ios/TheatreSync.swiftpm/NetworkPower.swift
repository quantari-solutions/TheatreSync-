import Foundation
import Network
import CryptoKit

/// Projector & receiver on / off over the Wi-Fi without knowing their model (mirror of Android's
/// NetworkPower.kt): PJLink (projectors, port 4352), Denon / Marantz (23), Onkyo / Pioneer / Integra
/// eISCP (60128), Yamaha MusicCast (HTTP). The Chromecast itself can only wake things (HDMI-CEC).
final class NetworkPower: ObservableObject {
    enum Kind: String, CaseIterable, Codable {
        case pjlink, denon, onkyo, yamaha
        var port: UInt16 { switch self { case .pjlink: 4352; case .denon: 23; case .onkyo: 60128; case .yamaha: 80 } }
        var label: String {
            switch self {
            case .pjlink: "Projector (PJLink)"; case .denon: "Denon / Marantz receiver"
            case .onkyo: "Onkyo / Pioneer / Integra receiver"; case .yamaha: "Yamaha receiver"
            }
        }
    }
    struct Device: Codable, Hashable { let kind: Kind; let host: String }

    @Published private(set) var devices: [Device] = []
    @Published private(set) var searching = false
    private var searchedAt: Date?

    init() {
        if let d = UserDefaults.standard.data(forKey: "powerDevices"), let list = try? JSONDecoder().decode([Device].self, from: d) { devices = list }
    }

    var labels: [String] { devices.map { "\($0.kind.label) · \($0.host)" }.sorted() }

    // MARK: - Finding them

    func search(force: Bool = false) {
        if searching { return }
        if !force, let at = searchedAt, Date().timeIntervalSince(at) < 600 { return }
        guard let prefix = LocalAddress.subnetPrefix(), let me = LocalAddress.wifiIPv4() else { return }
        searching = true
        Task.detached { [weak self] in
            var found: [Device] = []
            await withTaskGroup(of: Device?.self) { group in
                var running = 0
                for i in 1...254 {
                    let host = "\(prefix).\(i)"
                    if host == me { continue }
                    for kind in Kind.allCases {
                        if running >= 40 { if let d = await group.next() ?? nil { found.append(d) }; running -= 1 }
                        group.addTask { await Self.probe(host: host, kind: kind) }
                        running += 1
                    }
                }
                for await d in group { if let d { found.append(d) } }
            }
            await MainActor.run {
                guard let self else { return }
                let all = Array(Set(self.devices + found))
                self.devices = all
                if let d = try? JSONEncoder().encode(all) { UserDefaults.standard.set(d, forKey: "powerDevices") }
                self.searching = false
                self.searchedAt = Date()
            }
        }
    }

    // MARK: - On / off

    /// True if at least one device accepted.
    func setPower(on: Bool) async -> Bool {
        var ok = false
        for d in devices where await Self.setPower(d, on: on) { ok = true }
        return ok
    }

    // MARK: - The languages

    static func eiscp(_ command: String) -> Data {
        let body = Data("!1\(command)\r".utf8)
        var out = Data("ISCP".utf8)
        out.append(contentsOf: [0, 0, 0, 16])
        let n = UInt32(body.count).bigEndian
        out.append(contentsOf: withUnsafeBytes(of: n) { Array($0) })
        out.append(contentsOf: [1, 0, 0, 0])
        out.append(body)
        return out
    }

    static func pjlinkAuth(_ random: String, password: String = "") -> String {
        Insecure.MD5.hash(data: Data((random + password).utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func probe(host: String, kind: Kind) async -> Device? {
        let d = Device(kind: kind, host: host)
        switch kind {
        case .pjlink:
            guard let r = await exchange(host, kind.port, send: nil, timeout: 0.6) else { return nil }
            return r.hasPrefix("PJLINK ") ? d : nil
        case .denon:
            guard let r = await exchange(host, kind.port, send: Data("PW?\r".utf8), timeout: 0.6) else { return nil }
            return (r.hasPrefix("PWON") || r.hasPrefix("PWSTANDBY")) ? d : nil
        case .onkyo:
            guard let r = await exchange(host, kind.port, send: eiscp("PWRQSTN"), timeout: 0.6) else { return nil }
            return (r.hasPrefix("ISCP") && r.contains("!1PWR")) ? d : nil
        case .yamaha:
            guard let r = await http("http://\(host)/YamahaExtendedControl/v1/system/getDeviceInfo", timeout: 0.8) else { return nil }
            return (r.contains("\"model_name\"") && r.contains("\"response_code\":0")) ? d : nil
        }
    }

    static func setPower(_ d: Device, on: Bool) async -> Bool {
        switch d.kind {
        case .pjlink:
            // One connection: the greeting carries the password challenge for this connection only.
            let r = await exchange(d.host, d.kind.port, send: nil, timeout: 3, afterGreeting: { greeting in
                let prefix = greeting.hasPrefix("PJLINK 1 ") ? pjlinkAuth(String(greeting.dropFirst(9)).trimmingCharacters(in: .whitespaces)) : ""
                return Data((prefix + "%1POWR \(on ? 1 : 0)\r").utf8)
            })
            return r?.hasPrefix("%1POWR=OK") == true || r?.hasPrefix("%1POWR=ERR3") == true
        case .denon:
            return await exchange(d.host, d.kind.port, send: Data((on ? "PWON\r" : "PWSTANDBY\r").utf8), timeout: 2, expectReply: false) != nil
        case .onkyo:
            return await exchange(d.host, d.kind.port, send: eiscp(on ? "PWR01" : "PWR00"), timeout: 2, expectReply: false) != nil
        case .yamaha:
            let r = await http("http://\(d.host)/YamahaExtendedControl/v1/main/setPower?power=\(on ? "on" : "standby")", timeout: 3)
            return r?.contains("\"response_code\":0") == true
        }
    }

    /// Connects, optionally skips a greeting line, sends `send`, returns the first reply ("" if none expected).
    private static func exchange(_ host: String, _ port: UInt16, send: Data?, timeout: Double, afterGreeting: ((String) -> Data)? = nil, expectReply: Bool = true) async -> String? {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
            let q = DispatchQueue(label: "power")
            var done = false
            func finish(_ v: String?) { q.async { if done { return }; done = true; c.cancel(); cont.resume(returning: v) } }
            q.asyncAfter(deadline: .now() + timeout) { finish(nil) }
            func read() {
                c.receive(minimumIncompleteLength: 1, maximumLength: 512) { data, _, _, err in
                    guard err == nil, let data, !data.isEmpty else { finish(nil); return }
                    finish(String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines))
                }
            }
            c.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    let go: (Data?) -> Void = { s in
                        if let s {
                            c.send(content: s, completion: .contentProcessed { _ in if expectReply { read() } else { finish("") } })
                        } else { read() }
                    }
                    if let afterGreeting {
                        c.receive(minimumIncompleteLength: 1, maximumLength: 512) { data, _, _, _ in
                            go(afterGreeting(String(decoding: data ?? Data(), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)))
                        }
                    } else { go(send) }
                case .failed, .cancelled: finish(nil)
                default: break
                }
            }
            c.start(queue: q)
        }
    }

    private static func http(_ url: String, timeout: Double) async -> String? {
        guard let u = URL(string: url) else { return nil }
        var r = URLRequest(url: u); r.timeoutInterval = timeout
        guard let (data, resp) = try? await URLSession.shared.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return String(decoding: data, as: UTF8.self)
    }
}
