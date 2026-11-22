// Runs the iOS app's shared (Foundation-only) code on Linux against the
// contract fixtures. Build/run: scripts/test-ios-shared.sh
// This verifies protocol + sync logic only — NOT the SwiftUI app or AVFoundation.
import Foundation

var failures = 0
var passed = 0
func check(_ ok: Bool, _ name: String) {
    if ok { passed += 1 } else { failures += 1; print("FAIL  \(name)") }
}

let contracts = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "contracts")

func files(_ kind: String) -> [URL] {
    let dir = contracts.appendingPathComponent("examples/\(kind)")
    let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
    return names.filter { $0.hasSuffix(".json") }.sorted().map { dir.appendingPathComponent($0) }
}

// 1. Every valid fixture decodes and re-encodes to the same JSON.
let valid = files("valid")
check(valid.count >= 6, "found valid fixtures")
for f in valid {
    let data = try! Data(contentsOf: f)
    do {
        let msg = try SyncCodec.decode(data)
        let again = try SyncCodec.encode(msg)
        let a = try JSONSerialization.jsonObject(with: data) as! NSDictionary
        let b = try JSONSerialization.jsonObject(with: again) as! NSDictionary
        check(a.isEqual(b), "round-trip \(f.lastPathComponent)")
        check(try SyncCodec.decode(again) == msg, "re-decode \(f.lastPathComponent)")
    } catch {
        check(false, "decode \(f.lastPathComponent): \(error)")
    }
}

// 2. Every invalid fixture is rejected.
for f in files("invalid") {
    let data = try! Data(contentsOf: f)
    check((try? SyncCodec.decode(data)) == nil, "reject \(f.lastPathComponent)")
}

// 3. Built-in self test used by the Diagnostics screen.
check(SyncProtocol.selfTest() == "pass", "selfTest")

// 4. Clock sync: symmetric path recovers the offset; jittery samples are filtered.
do {
    var cs = ClockSync()
    func exchange(_ t0: Int64, offset: Int64, up: Int64, down: Int64) {
        let t1 = t0 + up + offset
        let t2 = t1 + 1
        let t3 = t2 - offset + down
        cs.addExchange(ClockPong(seq: 0, t0: t0, t1: t1, t2: t2), t3: t3)
    }
    exchange(1_000, offset: 5_000, up: 10, down: 10)
    check(cs.offsetMs == 5_000, "clock offset exact")
    check(cs.localToHost(1_000) == 6_000, "localToHost")
    cs = ClockSync()
    for i in 0..<3 { exchange(1_000 + Int64(i) * 100, offset: 250, up: 4, down: 4) }
    for i in 0..<9 { exchange(5_000 + Int64(i) * 100, offset: 250, up: 120, down: 8) }
    check(abs(cs.offsetMs! - 250) <= 0.5, "clock filters jitter")
    check(!cs.addExchange(ClockPong(seq: 0, t0: 100, t1: 50, t2: 40), t3: 120), "clock rejects bad timestamps")
}

// 5. Drift corrector: same cases as the Kotlin DriftCorrectorTest.
do {
    var c = DriftCorrector()
    check(c.decide(localMs: 10_000, targetMs: 10_005) == .none, "in sync → none")
    c = DriftCorrector()
    check(c.decide(localMs: 12_000, targetMs: 10_000) == .seek(10_000), "big drift → seek")
    c = DriftCorrector()
    check(c.decide(localMs: 10_100, targetMs: 10_000) == .setSpeed(0.95), "ahead → slow")
    c = DriftCorrector()
    check(c.decide(localMs: 10_000, targetMs: 10_050) == .setSpeed(1.025), "behind → faster")
    c = DriftCorrector()
    _ = c.decide(localMs: 10_060, targetMs: 10_000)
    check(c.decide(localMs: 10_020, targetMs: 10_000) == .setSpeed(0.99), "hysteresis keeps correcting")
    check(c.decide(localMs: 10_005, targetMs: 10_000) == .setSpeed(1.0), "hysteresis stops")
    check(c.decide(localMs: 10_030, targetMs: 10_000) == .none, "dead band")

    var f = DriftCorrector()
    check(f.decide(localMs: 10_020, targetMs: 10_000) == .none, "20 ms ignored")
    f.forceCorrect()
    check(f.decide(localMs: 10_020, targetMs: 10_000) == .setSpeed(0.99), "forced 20 ms applied")

    // Closed loop: player clock 0.3 % fast, starts 300 ms behind.
    var cl = DriftCorrector()
    var local = 9_700.0
    var speed = 1.0
    var worst = 0.0
    for tick in 0..<400 {
        let target = 10_000.0 + Double(tick) * 250
        switch cl.decide(localMs: Int64(local), targetMs: Int64(target)) {
        case .setSpeed(let s): speed = s
        case .seek(let p): local = Double(p)
        case .none: break
        }
        local += 250 * speed * 1.003
        if tick > 40 { worst = max(worst, abs(local - (target + 250))) }
    }
    check(worst <= 45, "closed loop holds within 45 ms (worst \(worst))")
}

// 6. Listener target.
let s = PlaybackState(state: .playing, mediaPositionMs: 60_000, hostTimeMs: 1_000, playbackRate: 1.0, mediaTitle: nil)
check(ListenerTarget.targetMs(state: s, hostNowMs: 1_500, soundDelayMs: 120) == 60_380, "listener target")

// 7. Cast protocol (mirror of CastV2.kt): frames round-trip, status parsing, picture load.
do {
    let m = CastMessage(sourceId: "sender-ts-ios", destinationId: "receiver-0", namespace: CastMessage.nsReceiver, payload: "{\"type\":\"GET_STATUS\",\"requestId\":1}")
    let f = m.frame()
    check(CastMessage.frameLength(f.prefix(4)) == f.count - 4, "cast frame length")
    check(CastMessage.decode(f.dropFirst(4)) == m, "cast message round-trip")
    let status = "{\"type\":\"RECEIVER_STATUS\",\"status\":{\"volume\":{\"level\":0.4,\"muted\":false},\"applications\":[{\"appId\":\"233637DE\",\"displayName\":\"YouTube\",\"sessionId\":\"s1\",\"transportId\":\"t1\",\"namespaces\":[{\"name\":\"urn:x-cast:com.google.cast.media\"}]}]}}"
    let a = CastStatus.parseReceiver(status)
    check(a?.kind == .youtube && a?.transportId == "t1" && a?.volumeLevel == 0.4, "cast receiver status")
    let media = "{\"type\":\"MEDIA_STATUS\",\"status\":[{\"mediaSessionId\":3,\"playerState\":\"PLAYING\",\"currentTime\":12.5,\"media\":{\"contentId\":\"dQw4w9WgXcQ\",\"metadata\":{\"title\":\"Song\"}}}]}"
    let merged = a.map { CastStatus.mergeMedia($0, media) }
    check(merged?.isPlaying == true && merged?.canControl == true && merged?.imageUrl?.contains("dQw4w9WgXcQ") == true, "cast media status")
    let load = CastStatus.loadPicture(url: "http://192.168.1.5:5000/card.webp", requestId: 2)
    check(load.contains("\"streamType\":\"NONE\"") && load.contains("image/webp") && !load.contains("title"), "title card is a picture (no player bar)")
}

print("\(passed) passed, \(failures) failed")
exit(failures == 0 ? 0 : 1)
