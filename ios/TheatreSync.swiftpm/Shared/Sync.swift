import Foundation

// Swift mirror of android/sync-core PlaybackClock, DriftCorrector and ListenerTarget.
// Keep the numbers identical to the Kotlin versions.

enum PlaybackClock {
    static func positionAt(_ state: PlaybackState, hostNowMs: Int64) -> Int64 {
        guard state.state == .playing else { return state.mediaPositionMs }
        let elapsed = max(0, hostNowMs - state.hostTimeMs)
        return state.mediaPositionMs + Int64((Double(elapsed) * state.playbackRate).rounded())
    }
}

enum ListenerTarget {
    static func targetMs(state: PlaybackState, hostNowMs: Int64, soundDelayMs: Int) -> Int64 {
        max(0, PlaybackClock.positionAt(state, hostNowMs: hostNowMs) - Int64(soundDelayMs))
    }
}

struct DriftCorrector {
    enum Action: Equatable {
        case none
        case setSpeed(Double)
        case seek(Int64)
    }

    var startCorrectingMs: Int64 = 40
    var stopCorrectingMs: Int64 = 10
    var seekThresholdMs: Int64 = 400
    var maxSpeedDelta: Double = 0.05
    var catchUpMs: Int64 = 2_000

    private var correcting = false
    private var currentSpeed = 1.0

    mutating func decide(localMs: Int64, targetMs: Int64) -> Action {
        let drift = localMs - targetMs
        let magnitude = abs(drift)
        if magnitude >= seekThresholdMs {
            correcting = false
            currentSpeed = 1.0
            return .seek(max(0, targetMs))
        }
        if correcting && magnitude <= stopCorrectingMs {
            correcting = false
        } else if !correcting && magnitude >= startCorrectingMs {
            correcting = true
        }
        var wanted = 1.0
        if correcting {
            let raw = 1.0 - Double(drift) / Double(catchUpMs)
            let clamped = min(1.0 + maxSpeedDelta, max(1.0 - maxSpeedDelta, raw))
            wanted = (clamped * 200).rounded() / 200
        }
        if wanted == currentSpeed { return .none }
        currentSpeed = wanted
        return .setSpeed(wanted)
    }

    mutating func reset() {
        correcting = false
        currentSpeed = 1.0
    }
}
