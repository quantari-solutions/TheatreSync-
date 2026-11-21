package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndlessHlsTest {
    private var now = 0L

    /** One TS packet: PES start with PTS+DTS, and an adaptation field with a PCR. */
    private fun packet(pts: Long, dts: Long, pcr: Long): ByteArray {
        val b = ByteArray(188) { 0xFF.toByte() }
        b[0] = 0x47; b[1] = 0x41; b[2] = 0x00; b[3] = 0x30 // PUSI, PID 0x100, adaptation + payload
        b[4] = 7; b[5] = 0x10 // adaptation length 7, PCR flag
        b[6] = (pcr shr 25).toByte(); b[7] = (pcr shr 17).toByte(); b[8] = (pcr shr 9).toByte(); b[9] = (pcr shr 1).toByte()
        b[10] = (((pcr and 1).toInt() shl 7) or 0x7E).toByte(); b[11] = 0
        val i = 12
        b[i] = 0; b[i + 1] = 0; b[i + 2] = 1; b[i + 3] = 0xE0.toByte(); b[i + 4] = 0; b[i + 5] = 0
        b[i + 6] = 0x80.toByte(); b[i + 7] = 0xC0.toByte(); b[i + 8] = 10
        b[i + 9] = 0x30; TsRetimer.writeTs(b, i + 9, pts)
        b[i + 14] = 0x10; TsRetimer.writeTs(b, i + 14, dts)
        return b
    }

    private fun pcrOf(b: ByteArray): Long {
        fun u(i: Int) = b[i].toLong() and 0xFF
        return (u(6) shl 25) or (u(7) shl 17) or (u(8) shl 9) or (u(9) shl 1) or (u(10) shr 7)
    }

    @Test fun timestampsAreShiftedExactly() {
        val shifted = TsRetimer.shift(packet(126_000, 117_000, 120_000), 900_000)
        assertEquals(1_026_000L, TsRetimer.readTs(shifted, 21))
        assertEquals(1_017_000L, TsRetimer.readTs(shifted, 26))
        assertEquals(1_020_000L, pcrOf(shifted))
        assertEquals(0x30, shifted[21].toInt() and 0xF0) // PTS prefix kept
        assertEquals(0x7E, shifted[10].toInt() and 0x7E) // PCR reserved bits kept
    }

    @Test fun eachRoundPlaysEverySceneOnceAndNeverTwiceInARow() {
        val hls = EndlessHls(12, { ByteArray(0) }, clockMs = { now }, seed = 7)
        val seq = (0L until 12 * 50).map { hls.sceneAt(it) }
        seq.chunked(12).forEach { assertEquals((0 until 12).toSet(), it.toSet()) }
        seq.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        // Rounds differ from each other (it doesn't loop).
        assertTrue(seq.chunked(12).distinct().size > 45)
    }

    @Test fun playlistMovesOnWithTime() {
        val hls = EndlessHls(3, { ByteArray(0) }, clockMs = { now }, seed = 1)
        val p0 = hls.playlist()
        assertTrue(p0.contains("#EXT-X-MEDIA-SEQUENCE:0") && p0.contains("seg/0.ts") && p0.contains("seg/4.ts"))
        assertTrue(!p0.contains("#EXT-X-ENDLIST")) // live: never ends
        now += 25_000
        assertTrue(hls.playlist().contains("#EXT-X-MEDIA-SEQUENCE:2"))
    }

    @Test fun segmentNFollowsSegmentNMinusOne() {
        val scene = packet(126_000, 126_000, 126_000)
        val hls = EndlessHls(2, { scene }, clockMs = { now }, seed = 3)
        assertEquals(126_000L + 3 * 900_000, TsRetimer.readTs(hls.segment(3), 21))
    }

    @Test fun servedOverHttpWithCors() {
        val server = MediaServer("h", { now }, { null }, { Payload.Hello("T", Platform.ANDROID, Role.HOST, "1", listOf(1)) })
        server.live = EndlessHls(2, { packet(1, 1, 1) }, clockMs = { now }, seed = 1)
        val port = server.start()
        try {
            val c = java.net.URL("http://127.0.0.1:$port/live.m3u8").openConnection() as java.net.HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
            // Exactly one CORS header: browsers (and the Chromecast's player) reject "*, *".
            assertEquals(1, c.headerFields.entries.single { it.key.equals("Access-Control-Allow-Origin", true) }.value.size)
            assertTrue(c.inputStream.readBytes().decodeToString().startsWith("#EXTM3U"))
            val s = java.net.URL("http://127.0.0.1:$port/seg/2.ts").openConnection() as java.net.HttpURLConnection
            assertEquals(188, s.inputStream.readBytes().size)
        } finally { server.stop() }
    }

    /** Manual check in a browser (hls.js): TS_HLS_DIR=<dir with ss01.ts…> serves the real scenes for 90 s on port 8790. */
    @Test fun serveRealScenesForBrowserCheck() {
        val dir = System.getenv("TS_HLS_DIR") ?: return
        val files = java.io.File(dir).listFiles { f -> f.name.endsWith(".ts") }!!.sortedBy { it.name }
        val server = MediaServer("h", { System.currentTimeMillis() }, { null }, { Payload.Hello("T", Platform.ANDROID, Role.HOST, "1", listOf(1)) })
        server.live = EndlessHls(files.size, { files[it].readBytes() }, segmentSec = (System.getenv("TS_HLS_SEC") ?: "15").toInt(), clockMs = { System.currentTimeMillis() })
        server.start(8790)
        Thread.sleep(110_000)
        server.stop()
    }
}
