package com.theatresync.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class IntroHlsTest {
    private var now = 1_000L
    private val hls = IntroHls(intro = { ByteArray(188).also { it[0] = 0x47 } }, introMs = 6_600, tail = { ByteArray(188).also { it[0] = 0x47 } }, clockMs = { now })

    @Test fun startsWithOnlyTheIntroSoTheTvBeginsAtItsFirstFrame() {
        val p = hls.playlist()
        assertTrue(p.contains("seg/0.ts"))
        assertFalse(p.contains("seg/1.ts"))
        assertFalse(p.contains("ENDLIST")) // live: no progress bar on the TV
        assertTrue(p.contains("#EXTINF:6.600,"))
    }

    @Test fun onlyTheIntroIsListedUntilTheTvFetchesIt() {
        hls.playlist()
        now += 30_000             // the list was read early, the player joined late: still starts at the intro
        assertFalse(hls.playlist().contains("seg/1.ts"))
    }

    @Test fun blackFollowsAheadOfTimeSoItNeverStalls() {
        hls.segment(0)            // the TV fetching the intro starts the clock
        now += 4_000              // 3 s lead: the first black segment is listed before the intro ends
        assertTrue(hls.playlist().contains("seg/1.ts"))
        now += 10_000
        val p = hls.playlist()
        assertTrue(p.contains("seg/6.ts"))
        assertEquals(1, Regex("#EXT-X-MEDIA-SEQUENCE:0").findAll(p).count())
    }

    @Test fun segmentsAreServed() {
        assertEquals(188, hls.segment(0).size)
        assertEquals(188, hls.segment(3).size)
    }

    /** Manual check in a browser (hls.js): TS_INTRO_DIR=<dir with signature_intro.ts, intro_tail.ts> serves it for 40 s on port 8790. */
    @Test fun serveRealIntroForBrowserCheck() {
        val dir = System.getenv("TS_INTRO_DIR") ?: return
        val server = MediaServer("h", { System.currentTimeMillis() }, { null }, { Payload.Hello("T", Platform.ANDROID, Role.HOST, "1", listOf(1)) })
        server.live = IntroHls({ java.io.File(dir, "signature_intro.ts").readBytes() }, 6_600, { java.io.File(dir, "intro_tail.ts").readBytes() }, clockMs = { System.currentTimeMillis() })
        server.start(8790)
        Thread.sleep(40_000)
        server.stop()
    }
}
