import Foundation
import Network

/// Finds TheatreSync movies announced on the Wi-Fi by the Android phone (Bonjour).
/// The phone puts its IP address and port in the TXT record.
final class HostBrowser: ObservableObject {
    struct Host: Identifiable, Hashable {
        let id: String
        let name: String
        let address: String
    }

    @Published private(set) var hosts: [Host] = []
    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_theatresync._tcp", domain: nil), using: parameters)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            var found: [Host] = []
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint else { continue }
                guard case let .bonjour(txt) = result.metadata else { continue }
                guard let ip = txt["ip"], let port = txt["port"] else { continue }
                found.append(Host(id: name, name: name, address: "\(ip):\(port)"))
            }
            found.sort { $0.name < $1.name }
            DispatchQueue.main.async {
                self?.hosts = found
            }
        }
        b.start(queue: .main)
        browser = b
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }
}
