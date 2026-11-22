import Foundation

/// Minimal Google Cast v2 wire protocol (mirror of android/sync-core CastV2.kt): the length-prefixed
/// protobuf `CastMessage` every Chromecast speaks on TLS port 8009. Foundation only, so it's compiled
/// and tested on Linux too.
struct CastMessage: Equatable {
    var sourceId: String
    var destinationId: String
    var namespace: String
    var payload: String

    static let nsConnection = "urn:x-cast:com.google.cast.tp.connection"
    static let nsHeartbeat = "urn:x-cast:com.google.cast.tp.heartbeat"
    static let nsReceiver = "urn:x-cast:com.google.cast.receiver"
    static let nsMedia = "urn:x-cast:com.google.cast.media"
    static let maxFrame = 64 * 1024

    func encode() -> Data {
        var out = Data()
        func varint(_ value: UInt64) {
            var x = value
            while true {
                if x & ~UInt64(0x7F) == 0 { out.append(UInt8(x)); return }
                out.append(UInt8((x & 0x7F) | 0x80)); x >>= 7
            }
        }
        func tag(_ field: Int, _ wire: Int) { varint(UInt64((field << 3) | wire)) }
        func str(_ field: Int, _ s: String) { let b = Data(s.utf8); tag(field, 2); varint(UInt64(b.count)); out.append(b) }
        tag(1, 0); varint(0)
        str(2, sourceId); str(3, destinationId); str(4, namespace)
        tag(5, 0); varint(0)
        str(6, payload)
        return out
    }

    /// One frame: 4-byte big-endian length, then the protobuf.
    func frame() -> Data {
        let body = encode()
        let n = UInt32(body.count).bigEndian
        return withUnsafeBytes(of: n) { Data($0) } + body
    }

    static func decode(_ data: Data) -> CastMessage? {
        let bytes = [UInt8](data)
        var i = 0
        func varint() -> UInt64? {
            var shift: UInt64 = 0, result: UInt64 = 0
            while i < bytes.count {
                let b = bytes[i]; i += 1
                result |= UInt64(b & 0x7F) << shift
                if b & 0x80 == 0 { return result }
                shift += 7
                if shift > 63 { return nil }
            }
            return nil
        }
        var m = CastMessage(sourceId: "", destinationId: "", namespace: "", payload: "")
        while i < bytes.count {
            guard let key = varint() else { return nil }
            let field = Int(key >> 3)
            switch key & 7 {
            case 0: guard varint() != nil else { return nil }
            case 2:
                guard let len = varint(), i + Int(len) <= bytes.count else { return nil }
                let s = String(decoding: bytes[i..<(i + Int(len))], as: UTF8.self)
                i += Int(len)
                switch field { case 2: m.sourceId = s; case 3: m.destinationId = s; case 4: m.namespace = s; case 6: m.payload = s; default: break }
            default: return nil
            }
        }
        return m
    }

    /// Length of a frame from its 4-byte header, or nil if out of range.
    static func frameLength(_ header: Data) -> Int? {
        guard header.count == 4 else { return nil }
        let b = [UInt8](header)
        let n = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
        return (n > 0 && n <= maxFrame) ? n : nil
    }
}

/// What a Chromecast is showing right now (mirror of CastActivity in CastV2.kt).
struct CastActivity: Equatable {
    enum Kind: String { case youtube, mirroring, media, other }
    var appId: String
    var appName: String
    var kind: Kind
    var title: String? = nil
    var subtitle: String? = nil
    var imageUrl: String? = nil
    var playerState: String? = nil
    var currentTimeSec: Double? = nil
    var durationSec: Double? = nil
    var mediaSessionId: Int? = nil
    var transportId: String? = nil
    var hasMedia = false
    var sessionId: String? = nil
    var volumeLevel: Double? = nil
    var muted: Bool? = nil
    var contentId: String? = nil

    var isPlaying: Bool { playerState == "PLAYING" || playerState == "BUFFERING" }
    var canControl: Bool { mediaSessionId != nil && transportId != nil }
}

/// Parses Cast receiver / media status JSON (mirror of CastStatus in CastV2.kt).
enum CastStatus {
    static let appYouTube = "233637DE"
    static let appDefaultMediaReceiver = "CC1AD845"
    static let appBackdrop = "E8C28D3C"
    static let mirroringApps: Set<String> = ["0F5096E8", "674A0243", "85CDB22F", "8E6C866D"]

    static func json(_ payload: String) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
    }

    static func type(_ payload: String) -> String? { json(payload)?["type"] as? String }

    /// Volume from RECEIVER_STATUS (also when the TV is idle).
    static func volume(_ payload: String) -> (level: Double?, muted: Bool?) {
        let v = (json(payload)?["status"] as? [String: Any])?["volume"] as? [String: Any]
        return ((v?["level"] as? NSNumber)?.doubleValue, (v?["muted"] as? NSNumber)?.boolValue)
    }

    /// From RECEIVER_STATUS: the app in the foreground, or nil if the TV is idle.
    static func parseReceiver(_ payload: String) -> CastActivity? {
        guard let status = json(payload)?["status"] as? [String: Any],
              let apps = status["applications"] as? [[String: Any]] else { return nil }
        guard let app = apps.first(where: { a in
            guard let id = a["appId"] as? String else { return false }
            return id != appBackdrop && (a["isIdleScreen"] as? NSNumber)?.boolValue != true
        }), let appId = app["appId"] as? String else { return nil }
        let name = app["displayName"] as? String ?? "Cast"
        let namespaces = (app["namespaces"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
        let kind: CastActivity.Kind
        if appId == appYouTube { kind = .youtube }
        else if mirroringApps.contains(appId) || name.lowercased().contains("mirror") { kind = .mirroring }
        else if namespaces.contains(CastMessage.nsMedia) { kind = .media }
        else { kind = .other }
        let vol = volume(payload)
        return CastActivity(appId: appId, appName: name, kind: kind,
                            transportId: app["transportId"] as? String,
                            hasMedia: namespaces.contains(CastMessage.nsMedia),
                            sessionId: app["sessionId"] as? String,
                            volumeLevel: vol.level, muted: vol.muted)
    }

    /// Merges a MEDIA_STATUS payload into `base`.
    static func mergeMedia(_ base: CastActivity, _ payload: String) -> CastActivity {
        guard let status = (json(payload)?["status"] as? [[String: Any]])?.first else { return base }
        var a = base
        let media = status["media"] as? [String: Any]
        let meta = media?["metadata"] as? [String: Any]
        let contentId = media?["contentId"] as? String
        let image = ((meta?["images"] as? [[String: Any]])?.first?["url"] as? String)
            ?? ((base.kind == .youtube && contentId.map(isYouTubeId) == true) ? "https://i.ytimg.com/vi/\(contentId!)/hqdefault.jpg" : nil)
        a.title = meta?["title"] as? String ?? a.title
        a.contentId = contentId ?? a.contentId
        a.subtitle = (meta?["subtitle"] ?? meta?["artist"] ?? meta?["seriesTitle"]) as? String ?? a.subtitle
        a.imageUrl = image ?? a.imageUrl
        a.playerState = status["playerState"] as? String ?? a.playerState
        a.currentTimeSec = (status["currentTime"] as? NSNumber)?.doubleValue ?? a.currentTimeSec
        if let d = (media?["duration"] as? NSNumber)?.doubleValue, d > 0 { a.durationSec = d }
        a.mediaSessionId = (status["mediaSessionId"] as? NSNumber)?.intValue ?? a.mediaSessionId
        return a
    }

    static func isYouTubeId(_ s: String) -> Bool {
        s.count == 11 && s.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
    }

    // Requests (mirrors of the payloads the Android app sends).
    static func setVolume(_ level: Double, requestId: Int) -> String {
        "{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":\(max(0, min(1, level)))},\"requestId\":\(requestId)}"
    }
    static func setMuted(_ muted: Bool, requestId: Int) -> String {
        "{\"type\":\"SET_VOLUME\",\"volume\":{\"muted\":\(muted)},\"requestId\":\(requestId)}"
    }
    static func stop(sessionId: String, requestId: Int) -> String {
        "{\"type\":\"STOP\",\"sessionId\":\"\(sessionId)\",\"requestId\":\(requestId)}"
    }
    static func launch(_ appId: String, requestId: Int) -> String {
        "{\"type\":\"LAUNCH\",\"appId\":\"\(appId)\",\"requestId\":\(requestId)}"
    }
    static func media(_ type: String, sessionId: Int, requestId: Int, extra: String = "") -> String {
        "{\"type\":\"\(type)\",\"mediaSessionId\":\(sessionId),\"requestId\":\(requestId)\(extra)}"
    }
    /// The Signature title card as a picture: full-screen on the TV, no player bar, "LIVE" or time.
    static func loadPicture(url: String, contentType: String = "image/webp", requestId: Int) -> String {
        "{\"type\":\"LOAD\",\"requestId\":\(requestId),\"autoplay\":true,\"media\":{\"contentId\":\"\(url)\",\"contentType\":\"\(contentType)\",\"streamType\":\"NONE\",\"metadata\":{\"metadataType\":4}}}"
    }
}
