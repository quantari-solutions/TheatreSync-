import Foundation
import AVFoundation
import UIKit

/// Plays the movie's sound on this iPhone/iPad, in step with the TV picture.
///
/// Every 250 ms it asks the Android phone where the picture is (/state), keeps an
/// estimate of the difference between the two devices' clocks (/clock), and nudges
/// the player's speed (or seeks) so the sound lines up. Same rules as Android.
@MainActor
final class ListenerSession: ObservableObject {
    enum Status: Equatable {
        case idle
        case connecting
        case waiting
        case playing
        case paused
        case lost(String)
    }

    @Published private(set) var status: Status = .idle
    @Published private(set) var movieTitle: String = ""
    @Published private(set) var hostName: String = ""
    /// Automatic, like Android: the headphones' own delay as iOS reports it (Bluetooth adds ~150–250 ms),
    /// so the sound is played that much earlier. No manual buttons.
    var soundDelayMs: Int { -Int((AVAudioSession.sharedInstance().outputLatency * 1000).rounded()) }
    @Published private(set) var driftMs: Int64?

    private var base: URL?
    private var player: AVPlayer?
    private var clock = ClockSync()
    private var corrector = DriftCorrector()
    private var loop: Task<Void, Never>?
    private var tickCount = 0
    private var failures = 0
    private let listenerId: String
    private let http: URLSession

    init() {
        if let saved = UserDefaults.standard.string(forKey: "listenerId") {
            listenerId = saved
        } else {
            let made = "ios-" + String(UUID().uuidString.prefix(12))
            UserDefaults.standard.set(made, forKey: "listenerId")
            listenerId = made
        }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 2
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        http = URLSession(configuration: config)
    }


    func join(address: String, name: String) {
        leave()
        guard !address.isEmpty, let url = URL(string: "http://\(address)") else {
            status = .lost("That address doesn't look right.")
            return
        }
        base = url
        hostName = name
        status = .connecting
        clock = ClockSync()
        corrector = DriftCorrector()
        failures = 0
        tickCount = 0

        let audio = AVAudioSession.sharedInstance()
        try? audio.setCategory(.playback, mode: .moviePlayback)
        try? audio.setActive(true)

        let item = AVPlayerItem(url: url.appendingPathComponent("media"))
        item.audioTimePitchAlgorithm = .timeDomain
        let p = AVPlayer(playerItem: item)
        p.automaticallyWaitsToMinimizeStalling = false
        player = p
        UIApplication.shared.isIdleTimerDisabled = true

        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.tick()
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
    }

    func leave() {
        loop?.cancel()
        loop = nil
        player?.pause()
        player = nil
        base = nil
        driftMs = nil
        movieTitle = ""
        status = .idle
        UIApplication.shared.isIdleTimerDisabled = false
    }

    // MARK: - Sync loop

    private func tick() async {
        guard let base else { return }
        do {
            if clock.sampleCount < 8 || tickCount % 4 == 0 {
                try await ping(base)
            }
            tickCount += 1
            let state = try await fetchState(base)
            failures = 0
            movieTitle = state.mediaTitle ?? ""
            apply(state)
        } catch {
            failures += 1
            if failures >= 8 {
                player?.pause()
                corrector.reset()
                status = .lost("Can't reach the movie. Check this \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone") is on the same Wi-Fi.")
            }
        }
    }

    private func ping(_ base: URL) async throws {
        guard var components = URLComponents(url: base.appendingPathComponent("clock"), resolvingAgainstBaseURL: false) else { return }
        let t0 = Self.nowMs()
        components.queryItems = [
            URLQueryItem(name: "seq", value: String(tickCount)),
            URLQueryItem(name: "t0", value: String(t0)),
            URLQueryItem(name: "id", value: listenerId),
        ]
        guard let url = components.url else { return }
        let (data, _) = try await http.data(from: url)
        let t3 = Self.nowMs()
        if case .clockPong(let pong) = try SyncCodec.decode(data).payload {
            clock.addExchange(pong, t3: t3)
        }
    }

    private func fetchState(_ base: URL) async throws -> PlaybackState {
        let (data, _) = try await http.data(from: base.appendingPathComponent("state"))
        guard case .playbackState(let state) = try SyncCodec.decode(data).payload else {
            throw ProtocolError.outOfRange("state")
        }
        return state
    }

    private func apply(_ state: PlaybackState) {
        guard let player else { return }
        guard let hostNow = clock.localToHost(Self.nowMs()) else {
            status = .connecting
            return
        }
        switch state.state {
        case .playing:
            let target = ListenerTarget.targetMs(state: state, hostNowMs: hostNow, soundDelayMs: soundDelayMs)
            if player.timeControlStatus == .paused {
                corrector.reset()
                seek(player, to: target)
                player.playImmediately(atRate: 1.0)
                status = .playing
                return
            }
            guard player.timeControlStatus == .playing else { return } // still buffering
            let local = Self.ms(player.currentTime())
            driftMs = local - target
            switch corrector.decide(localMs: local, targetMs: target) {
            case .setSpeed(let speed):
                player.rate = Float(speed)
            case .seek(let position):
                player.rate = 1.0
                seek(player, to: position)
            case .none:
                break
            }
            status = .playing
        case .paused, .stopped:
            if player.timeControlStatus != .paused {
                player.pause()
            }
            corrector.reset()
            driftMs = nil
            status = state.state == .paused ? .paused : .waiting
        case .buffering:
            status = .waiting
        }
    }

    private func seek(_ player: AVPlayer, to ms: Int64) {
        player.seek(to: CMTime(value: ms, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private static func nowMs() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    private static func ms(_ time: CMTime) -> Int64 {
        let seconds = time.seconds
        return seconds.isFinite ? Int64(seconds * 1000) : 0
    }
}
