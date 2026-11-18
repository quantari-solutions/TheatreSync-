import Foundation

// Mirrors contracts/schemas/sync-message.v1.schema.json.
// scripts/validate-ios-source.sh checks these enums against the schema in CI.

enum SyncProtocol {
    static let protocolVersion = 1

    /// Decodes a fixture and re-encodes it; returns "pass" or a failure reason.
    static func selfTest() -> String {
        let fixture = #"{"v":1,"type":"clock_pong","senderId":"moto-g-host-01","sentAtMs":10102,"payload":{"seq":7,"t0":5000,"t1":10100,"t2":10102}}"#
        do {
            let msg = try SyncCodec.decode(Data(fixture.utf8))
            guard case .clockPong(let pong) = msg.payload, pong.t1 == 10100 else { return "FAIL (wrong payload)" }
            let again = try SyncCodec.decode(try SyncCodec.encode(msg))
            return again == msg ? "pass" : "FAIL (round-trip mismatch)"
        } catch {
            return "FAIL (\(error))"
        }
    }
}

enum MessageType: String, Codable {
    case hello
    case clockPing = "clock_ping"
    case clockPong = "clock_pong"
    case playbackState = "playback_state"
    case latencyReport = "latency_report"
    case error
}

enum Platform: String, Codable {
    case android
    case ios
}

enum Role: String, Codable {
    case host
    case listener
    case remote
}

enum PlaybackStatus: String, Codable {
    case playing
    case paused
    case buffering
    case stopped
}

enum OutputRoute: String, Codable {
    case bluetoothA2dp = "bluetooth_a2dp"
    case bluetoothLeAudio = "bluetooth_le_audio"
    case wired
    case speaker
    case cast
    case unknown
}

enum ErrorCode: String, Codable {
    case unsupportedVersion = "unsupported_version"
    case badMessage = "bad_message"
    case notHost = "not_host"
    case `internal`
}

struct Hello: Codable, Equatable {
    var deviceName: String
    var platform: Platform
    var role: Role
    var appVersion: String
    var protocolVersions: [Int]
}

struct ClockPing: Codable, Equatable {
    var seq: Int
    var t0: Int64
}

struct ClockPong: Codable, Equatable {
    var seq: Int
    var t0: Int64
    var t1: Int64
    var t2: Int64
}

struct PlaybackState: Codable, Equatable {
    var state: PlaybackStatus
    var mediaPositionMs: Int64
    var hostTimeMs: Int64
    var playbackRate: Double
    var mediaTitle: String?
}

struct LatencyReport: Codable, Equatable {
    var outputRoute: OutputRoute
    var outputDeviceName: String?
    var estimatedOutputLatencyMs: Int
    var userOffsetMs: Int
}

struct ErrorPayload: Codable, Equatable {
    var code: ErrorCode
    var message: String
}

enum Payload: Equatable {
    case hello(Hello)
    case clockPing(ClockPing)
    case clockPong(ClockPong)
    case playbackState(PlaybackState)
    case latencyReport(LatencyReport)
    case error(ErrorPayload)

    var type: MessageType {
        switch self {
        case .hello: return .hello
        case .clockPing: return .clockPing
        case .clockPong: return .clockPong
        case .playbackState: return .playbackState
        case .latencyReport: return .latencyReport
        case .error: return .error
        }
    }
}

struct SyncMessage: Equatable {
    var senderId: String
    var sentAtMs: Int64
    var payload: Payload
}

enum ProtocolError: Error, Equatable {
    case unsupportedVersion(Int)
    case outOfRange(String)
}

enum SyncCodec {
    private struct Header: Decodable {
        var v: Int
        var type: MessageType
        var senderId: String
        var sentAtMs: Int64
    }

    private struct Envelope<P: Codable>: Codable {
        var v: Int
        var type: MessageType
        var senderId: String
        var sentAtMs: Int64
        var payload: P
    }

    static func decode(_ data: Data) throws -> SyncMessage {
        let decoder = JSONDecoder()
        let header = try decoder.decode(Header.self, from: data)
        guard header.v == SyncProtocol.protocolVersion else { throw ProtocolError.unsupportedVersion(header.v) }

        func body<P: Codable>(_ type: P.Type) throws -> P {
            try decoder.decode(Envelope<P>.self, from: data).payload
        }

        let payload: Payload
        switch header.type {
        case .hello: payload = .hello(try body(Hello.self))
        case .clockPing: payload = .clockPing(try body(ClockPing.self))
        case .clockPong: payload = .clockPong(try body(ClockPong.self))
        case .playbackState: payload = .playbackState(try body(PlaybackState.self))
        case .latencyReport: payload = .latencyReport(try body(LatencyReport.self))
        case .error: payload = .error(try body(ErrorPayload.self))
        }
        try validate(payload)
        return SyncMessage(senderId: header.senderId, sentAtMs: header.sentAtMs, payload: payload)
    }

    static func encode(_ message: SyncMessage) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        func wrap<P: Codable>(_ p: P) throws -> Data {
            try encoder.encode(Envelope(
                v: SyncProtocol.protocolVersion,
                type: message.payload.type,
                senderId: message.senderId,
                sentAtMs: message.sentAtMs,
                payload: p
            ))
        }
        switch message.payload {
        case .hello(let p): return try wrap(p)
        case .clockPing(let p): return try wrap(p)
        case .clockPong(let p): return try wrap(p)
        case .playbackState(let p): return try wrap(p)
        case .latencyReport(let p): return try wrap(p)
        case .error(let p): return try wrap(p)
        }
    }

    private static func validate(_ payload: Payload) throws {
        switch payload {
        case .playbackState(let s):
            if s.mediaPositionMs < 0 { throw ProtocolError.outOfRange("mediaPositionMs") }
            if !(s.playbackRate > 0 && s.playbackRate <= 4) { throw ProtocolError.outOfRange("playbackRate") }
        case .latencyReport(let r):
            if !(0...2000).contains(r.estimatedOutputLatencyMs) { throw ProtocolError.outOfRange("estimatedOutputLatencyMs") }
            if !(-1000...1000).contains(r.userOffsetMs) { throw ProtocolError.outOfRange("userOffsetMs") }
        case .clockPong(let p):
            if p.t2 < p.t1 { throw ProtocolError.outOfRange("t2") }
        default:
            break
        }
    }
}
