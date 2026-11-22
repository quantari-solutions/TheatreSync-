package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTapInTest {
    @Test fun stateIsSafeJson() {
        assertEquals("""{"videoId":"dQw4w9WgXcQ","position":12.500,"playing":true}""", YouTubeTapIn.stateJson(YouTubeTapIn.State("dQw4w9WgXcQ", 12.5, true)))
        assertEquals("""{"videoId":"abc"}""".replace("abc\"", "abc\""), """{"videoId":"abc"}""") // sanity
        assertTrue(YouTubeTapIn.stateJson(YouTubeTapIn.State("x\"<script>", 1.0, false)).contains("\"videoId\":\"xscript\""))
        assertEquals("""{"videoId":null}""", YouTubeTapIn.stateJson(null))
    }

    @Test fun guestsGetThePageAndTheTvPosition() {
        val server = MediaServer("h", { 0L }, { null }, { Payload.Hello("T", Platform.ANDROID, Role.HOST, "1", listOf(1)) })
        server.youTube = { YouTubeTapIn.State("dQw4w9WgXcQ", 61.0, true) }
        val port = server.start()
        try {
            val page = java.net.URL("http://127.0.0.1:$port/yt").readText()
            assertTrue(page.contains("Start listening") && page.contains("iframe_api") && page.contains("/yt/state"))
            assertTrue(java.net.URL("http://127.0.0.1:$port/yt/state").readText().contains("\"position\":61.000"))
        } finally { server.stop() }
    }
}
