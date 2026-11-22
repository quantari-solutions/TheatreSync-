import Foundation

/// Settings → About: tells you when a newer iOS version is out. (Apple doesn't let an app install its own
/// updates; with TestFlight they'd install by themselves. Until then: replace the app with the new zip.)
final class UpdateCheck: ObservableObject {
    struct Info: Decodable { let version: String; let build: Int; let notes: String?; let zipUrl: String? }
    static let latestURL = URL(string: "https://raw.githubusercontent.com/quantari-solutions/TheatreSync-/main/releases/ios-latest.json")!

    @Published private(set) var available: Info?
    @Published private(set) var checked = false

    var currentVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?" }
    var currentBuild: Int { Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0") ?? 0 }

    func check() {
        var r = URLRequest(url: Self.latestURL); r.cachePolicy = .reloadIgnoringLocalCacheData; r.timeoutInterval = 10
        URLSession.shared.dataTask(with: r) { [weak self] data, _, _ in
            let info = data.flatMap { try? JSONDecoder().decode(Info.self, from: $0) }
            DispatchQueue.main.async {
                guard let self else { return }
                self.checked = true
                self.available = (info?.build ?? 0) > self.currentBuild ? info : nil
            }
        }.resume()
    }
}
