import Foundation

/// NTP-style host/listener clock offset estimation.
/// Same algorithm as android/sync-core/.../ClockSync.kt — keep them in step.
struct ClockSync {
    struct Sample {
        var offsetMs: Double
        var rttMs: Int64
    }

    private(set) var samples: [Sample] = []
    let maxSamples: Int
    let bestFraction: Double

    init(maxSamples: Int = 32, bestFraction: Double = 0.25) {
        self.maxSamples = maxSamples
        self.bestFraction = bestFraction
    }

    @discardableResult
    mutating func addExchange(_ pong: ClockPong, t3: Int64) -> Bool {
        let rtt = (t3 - pong.t0) - (pong.t2 - pong.t1)
        if rtt < 0 || pong.t2 < pong.t1 || t3 < pong.t0 { return false }
        let offset = Double((pong.t1 - pong.t0) + (pong.t2 - t3)) / 2.0
        samples.append(Sample(offsetMs: offset, rttMs: rtt))
        if samples.count > maxSamples { samples.removeFirst(samples.count - maxSamples) }
        return true
    }

    /// Host-minus-listener offset in ms, or nil with no samples.
    var offsetMs: Double? {
        guard !samples.isEmpty else { return nil }
        let n = max(1, Int(Double(samples.count) * bestFraction))
        let best = samples.sorted { $0.rttMs < $1.rttMs }.prefix(n).map(\.offsetMs).sorted()
        if best.count % 2 == 1 { return best[best.count / 2] }
        return (best[best.count / 2 - 1] + best[best.count / 2]) / 2.0
    }

    var sampleCount: Int { samples.count }

    func localToHost(_ localMs: Int64) -> Int64? {
        guard let o = offsetMs else { return nil }
        return Int64((Double(localMs) + o).rounded())
    }

    func hostToLocal(_ hostMs: Int64) -> Int64? {
        guard let o = offsetMs else { return nil }
        return Int64((Double(hostMs) - o).rounded())
    }
}
