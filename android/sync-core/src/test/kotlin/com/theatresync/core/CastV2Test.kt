package com.theatresync.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CastV2Test {
    // Captured from real Chromecast receivers (as documented by pychromecast / node-castv2), trimmed.
    private val youtubeReceiver = """{"requestId":2,"status":{"applications":[{"appId":"233637DE","displayName":"YouTube",
        "iconUrl":"","isIdleScreen":false,"launchedFromCloud":false,"namespaces":[{"name":"urn:x-cast:com.google.cast.debugoverlay"},
        {"name":"urn:x-cast:com.google.cast.cac"},{"name":"urn:x-cast:com.google.youtube.mdx"},{"name":"urn:x-cast:com.google.cast.media"}],
        "sessionId":"a1b2","statusText":"YouTube","transportId":"web-7"}],"volume":{"level":0.5,"muted":false}},"type":"RECEIVER_STATUS"}"""
    private val youtubeMedia = """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playbackRate":1,"playerState":"PLAYING",
        "currentTime":125.4,"supportedMediaCommands":274447,"media":{"contentId":"dQw4w9WgXcQ","streamType":"BUFFERED",
        "contentType":"x-youtube/video","metadata":{"metadataType":0,"title":"Never Gonna Give You Up","subtitle":"Rick Astley",
        "images":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}]},"duration":213.1}}],"requestId":0}"""
    private val mirroringReceiver = """{"requestId":5,"status":{"applications":[{"appId":"0F5096E8","displayName":"Chrome Mirroring",
        "isIdleScreen":false,"namespaces":[{"name":"urn:x-cast:com.google.cast.webrtc"},{"name":"urn:x-cast:com.google.cast.remoting"}],
        "sessionId":"m1","statusText":"Casting tab: Netflix","transportId":"web-9"}]},"type":"RECEIVER_STATUS"}"""
    private val idleReceiver = """{"requestId":1,"status":{"applications":[{"appId":"E8C28D3C","displayName":"Backdrop",
        "isIdleScreen":true,"namespaces":[],"sessionId":"b","statusText":"","transportId":"web-0"}]},"type":"RECEIVER_STATUS"}"""
    private val noAppsReceiver = """{"requestId":1,"status":{"volume":{"level":1.0,"muted":false}},"type":"RECEIVER_STATUS"}"""

    @Test fun protobufRoundTrip() {
        val m = CastMessage("sender-0", "receiver-0", CastMessage.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
        assertEquals(m, CastMessage.decode(CastMessage.encode(m)))
    }

    @Test fun protobufKnownBytes() {
        // Field 1 = 0, field 2 "a", field 3 "b", field 4 "c", field 5 = 0, field 6 "{}"
        val expected = byteArrayOf(0x08, 0x00, 0x12, 0x01, 'a'.code.toByte(), 0x1a, 0x01, 'b'.code.toByte(),
            0x22, 0x01, 'c'.code.toByte(), 0x28, 0x00, 0x32, 0x02, '{'.code.toByte(), '}'.code.toByte())
        assertTrue(expected.contentEquals(CastMessage.encode(CastMessage("a", "b", "c", "{}"))))
    }

    @Test fun framingRoundTripWithUnicodeAndLongPayload() {
        val long = "x".repeat(300) + "é🎬"
        val out = ByteArrayOutputStream()
        CastMessage.write(out, CastMessage("s", "d", "ns", long))
        CastMessage.write(out, CastMessage("s2", "d2", "ns2", "{}"))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(long, CastMessage.read(input).payloadUtf8)
        assertEquals("s2", CastMessage.read(input).sourceId)
    }

    @Test fun parsesYouTubeWithThumbnail() {
        val app = CastStatus.parseReceiver(youtubeReceiver)!!
        assertEquals(CastActivity.Kind.YOUTUBE, app.kind)
        assertEquals("web-7", app.transportId)
        assertTrue(app.hasMedia)
        val full = CastStatus.mergeMedia(app, youtubeMedia)
        assertEquals("Never Gonna Give You Up", full.title)
        assertEquals("Rick Astley", full.subtitle)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", full.imageUrl)
        assertEquals(125.4, full.currentTimeSec!!, 1e-9)
        assertEquals(213.1, full.durationSec!!, 1e-9)
        assertTrue(full.isPlaying)
        assertTrue(full.canControl)
    }

    @Test fun youTubeThumbnailFromVideoIdWhenNoImages() {
        val app = CastStatus.parseReceiver(youtubeReceiver)!!
        val media = youtubeMedia.replace(""","images":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}]""", "")
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", CastStatus.mergeMedia(app, media).imageUrl)
    }

    @Test fun parsesTabMirroring() {
        val app = CastStatus.parseReceiver(mirroringReceiver)!!
        assertEquals(CastActivity.Kind.MIRRORING, app.kind)
        assertEquals("Casting tab: Netflix", app.statusText)
        assertFalse(app.hasMedia)
        assertFalse(app.canControl)
    }

    @Test fun idleIsNull() {
        assertNull(CastStatus.parseReceiver(idleReceiver))
        assertNull(CastStatus.parseReceiver(noAppsReceiver))
        assertNull(CastStatus.parseReceiver("not json"))
    }

    @Test fun garbageMediaKeepsBase() {
        val app = CastStatus.parseReceiver(youtubeReceiver)!!
        assertEquals(app, CastStatus.mergeMedia(app, "{}"))
        assertEquals(app, CastStatus.mergeMedia(app, "nonsense"))
    }

    // ---- End-to-end against a fake Chromecast speaking the real framing ----

    private var fake: ServerSocket? = null
    @After fun stopFake() { fake?.close() }

    @Test(timeout = 15_000)
    fun watcherFollowsFakeChromecastFromIdleToYouTubeAndSendsPause() {
        val server = ServerSocket(0).also { fake = it }
        val received = CopyOnWriteArrayList<CastMessage>()
        val pauseSeen = CountDownLatch(1)
        val stopSeen = CountDownLatch(1)
        var appRunning = false
        Thread {
            server.accept().use { c ->
                val out = c.getOutputStream()
                val input = c.getInputStream()
                while (true) {
                    val m = try { CastMessage.read(input) } catch (_: Exception) { break }
                    received += m
                    val type = CastStatus.type(m.payloadUtf8)
                    when {
                        m.namespace == CastMessage.NS_HEARTBEAT && type == "PING" ->
                            CastMessage.write(out, CastMessage("receiver-0", m.sourceId, CastMessage.NS_HEARTBEAT, """{"type":"PONG"}"""))
                        m.namespace == CastMessage.NS_RECEIVER && type == "GET_STATUS" -> {
                            val payload = if (appRunning) youtubeReceiver else idleReceiver
                            CastMessage.write(out, CastMessage("receiver-0", m.sourceId, CastMessage.NS_RECEIVER, payload))
                            appRunning = true // next poll: YouTube has started
                        }
                        m.namespace == CastMessage.NS_MEDIA && type == "GET_STATUS" ->
                            CastMessage.write(out, CastMessage("web-7", m.sourceId, CastMessage.NS_MEDIA, youtubeMedia))
                        m.namespace == CastMessage.NS_MEDIA && type == "PAUSE" -> pauseSeen.countDown()
                        m.namespace == CastMessage.NS_RECEIVER && type == "STOP" && m.payloadUtf8.contains("\"sessionId\":\"a1b2\"") -> stopSeen.countDown()
                    }
                }
            }
        }.apply { isDaemon = true }.start()

        val updates = CopyOnWriteArrayList<CastActivity?>()
        val gotYouTube = CountDownLatch(1)
        val watcher = CastWatcher(
            connect = { Socket("127.0.0.1", server.localPort) },
            onUpdate = { a -> updates += a; if (a?.title != null) gotYouTube.countDown() },
            pollMs = 200,
        ).start()
        try {
            assertTrue("watcher never reported the YouTube video", gotYouTube.await(10, TimeUnit.SECONDS))
            val a = watcher.current!!
            assertEquals("Never Gonna Give You Up", a.title)
            assertEquals(CastActivity.Kind.YOUTUBE, a.kind)
            watcher.playPause(play = false)
            assertTrue("PAUSE not sent", pauseSeen.await(5, TimeUnit.SECONDS))
            val pause = received.first { CastStatus.type(it.payloadUtf8) == "PAUSE" }
            assertEquals("web-7", pause.destinationId)
            assertTrue(pause.payloadUtf8.contains("\"mediaSessionId\":3"))
            watcher.stopApp()
            assertTrue("STOP with the receiver session ID not sent", stopSeen.await(5, TimeUnit.SECONDS))
            assertTrue("must CONNECT to the app transport before media commands",
                received.any { it.destinationId == "web-7" && it.namespace == CastMessage.NS_CONNECTION })
        } finally {
            watcher.close()
        }
    }

    /** Regression for the 0.4.0 crash: close() must never touch the socket on the caller's thread. */
    @Test(timeout = 10_000)
    fun closeNeverClosesSocketOnCallerThread() {
        val server = ServerSocket(0).also { fake = it }
        val connected = CountDownLatch(1)
        val closedOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        val closedLatch = CountDownLatch(1)
        Thread { runCatching { server.accept().use { c -> c.getInputStream().read() } } }.apply { isDaemon = true }.start()
        val watcher = CastWatcher(
            connect = {
                object : Socket("127.0.0.1", server.localPort) {
                    override fun close() {
                        closedOn.compareAndSet(null, Thread.currentThread())
                        closedLatch.countDown()
                        super.close()
                    }
                }.also { connected.countDown() }
            },
            onUpdate = {},
            pollMs = 10_000,
        ).start()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        Thread.sleep(200) // let the watcher store the socket
        val caller = Thread.currentThread()
        watcher.close()
        assertTrue("socket was never closed", closedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("socket closed on the caller (main) thread", closedOn.get() !== caller)
    }
}
