package com.theatresync.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MediaServerTest {
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }
    private var now = 50_000L
    private var state: Payload.PlaybackState? = null
    private lateinit var server: MediaServer
    private lateinit var base: String

    private val movie = object : MediaSource {
        override val title = "Test movie"
        override val mimeType = "video/mp4"
        override val sizeBytes = bytes.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    @Before fun setUp() {
        server = MediaServer(
            senderId = "test-host-01",
            clockMs = { now },
            stateProvider = { state },
            helloProvider = { Payload.Hello("Test", Platform.ANDROID, Role.HOST, "0.2.0", listOf(1)) },
        )
        server.media = movie
        base = "http://127.0.0.1:${server.start()}"
    }

    @After fun tearDown() = server.stop()

    private fun get(path: String, range: String? = null, method: String = "GET"): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
            connectTimeout = 5_000
            readTimeout = 5_000
        }

    @Test fun fullDownload() {
        val c = get("/media")
        assertEquals(200, c.responseCode)
        assertEquals("video/mp4", c.contentType)
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertArrayEquals(bytes, c.inputStream.readBytes())
    }

    @Test fun rangeRequest() {
        val c = get("/media", "bytes=1000-1999")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 1000-1999/300000", c.getHeaderField("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(1000, 2000), c.inputStream.readBytes())
    }

    @Test fun openEndedAndSuffixRanges() {
        assertArrayEquals(bytes.copyOfRange(299_000, 300_000), get("/media", "bytes=299000-").inputStream.readBytes())
        assertArrayEquals(bytes.copyOfRange(299_900, 300_000), get("/media", "bytes=-100").inputStream.readBytes())
    }

    @Test fun unsatisfiableRange() {
        val c = get("/media", "bytes=400000-")
        assertEquals(416, c.responseCode)
        assertEquals("bytes */300000", c.getHeaderField("Content-Range"))
    }

    /** Speed fix: several range requests on ONE connection, as the Chromecast does. */
    @Test(timeout = 10_000)
    fun keepAliveServesSeveralRequestsOnOneConnection() {
        java.net.Socket("127.0.0.1", base.substringAfterLast(':').toInt()).use { sock ->
            val out = sock.getOutputStream()
            val input = java.io.BufferedInputStream(sock.getInputStream())
            fun request(range: String): Pair<Map<String, String>, ByteArray> {
                out.write("GET /media HTTP/1.1\r\nHost: x\r\nRange: bytes=$range\r\n\r\n".toByteArray())
                out.flush()
                val status = MediaServer.readLine(input)!!
                assertTrue(status, status.startsWith("HTTP/1.1 206"))
                val h = HashMap<String, String>()
                while (true) {
                    val line = MediaServer.readLine(input)!!
                    if (line.isEmpty()) break
                    h[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val body = ByteArray(h["content-length"]!!.toInt())
                var read = 0
                while (read < body.size) read += input.read(body, read, body.size - read)
                return h to body
            }
            val (h1, b1) = request("0-999")
            assertEquals("keep-alive", h1["connection"])
            assertArrayEquals(bytes.copyOfRange(0, 1000), b1)
            val (_, b2) = request("5000-5999")
            assertArrayEquals(bytes.copyOfRange(5000, 6000), b2)
            val (_, b3) = request("299000-")
            assertArrayEquals(bytes.copyOfRange(299000, 300000), b3)
        }
        assertEquals(3, server.mediaRequests)
        assertTrue(server.totalBytesSent >= 3000L)
    }

    @Test fun http10ClientsStillGetConnectionClose() {
        java.net.Socket("127.0.0.1", base.substringAfterLast(':').toInt()).use { sock ->
            sock.getOutputStream().write("GET /hello HTTP/1.0\r\n\r\n".toByteArray())
            val text = sock.getInputStream().readBytes().decodeToString()
            assertTrue(text.contains("Connection: close"))
        }
    }

    @Test fun countsMovieRequests() {
        assertEquals(0, server.mediaRequests)
        get("/media", "bytes=0-9").inputStream.readBytes()
        get("/state").inputStream.readBytes()
        assertEquals(1, server.mediaRequests)
    }

    @Test fun headHasNoBody() {
        val c = get("/media", method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals("300000", c.getHeaderField("Content-Length"))
    }

    @Test fun clockReturnsContractPongAndCountsListener() {
        val text = get("/clock?seq=3&t0=1234&id=iphone-friend").inputStream.readBytes().decodeToString()
        val msg = SyncCodec.decode(text)
        val pong = msg.payload as Payload.ClockPong
        assertEquals(3, pong.seq)
        assertEquals(1234L, pong.t0)
        assertEquals(50_000L, pong.t1)
        assertEquals(1, server.activeListeners())
        now += 10_000
        assertEquals(0, server.activeListeners())
    }

    @Test fun clockRejectsMissingParams() = assertEquals(400, get("/clock?seq=1").responseCode)

    @Test fun stateReflectsProvider() {
        val stopped = SyncCodec.decode(get("/state").inputStream.readBytes().decodeToString()).payload as Payload.PlaybackState
        assertEquals(Payload.PlaybackState.State.STOPPED, stopped.state)
        state = Payload.PlaybackState(Payload.PlaybackState.State.PLAYING, 90_000, 49_900, 1.0, "Test movie")
        val playing = SyncCodec.decode(get("/state").inputStream.readBytes().decodeToString()).payload as Payload.PlaybackState
        assertEquals(state, playing)
    }

    @Test fun brokenFileProviderDoesNotKillServer() {
        server.media = object : MediaSource {
            override val title = "Broken"
            override val mimeType = "video/mp4"
            override val sizeBytes = 1000L
            override fun open(offset: Long): InputStream = throw SecurityException("permission revoked")
        }
        runCatching { get("/media").inputStream.readBytes() } // request fails, that's fine
        server.media = movie
        assertEquals(200, get("/media").responseCode) // server still alive
    }

    // ---- TapIn: guests listen in a browser

    @Test fun listenPageIsServed() {
        val c = get("/listen")
        assertEquals(200, c.responseCode)
        assertTrue(c.contentType.startsWith("text/html"))
        val html = c.inputStream.readBytes().decodeToString()
        assertTrue(html.contains("/audio") && html.contains("/clock") && html.contains("/state"))
    }

    @Test fun audioServesTheMovieWithSoundEvenWhenTheTvGetsPictureOnly() {
        val pictureOnly = object : MediaSource {
            override val title = "Test movie"
            override val mimeType = "video/mp4"
            override val sizeBytes = 10L
            override fun open(offset: Long): InputStream = ByteArrayInputStream(ByteArray(10), offset.toInt(), 10 - offset.toInt())
        }
        server.media = pictureOnly
        server.audioMedia = movie
        assertEquals(10, get("/media").inputStream.readBytes().size)
        assertArrayEquals(bytes, get("/audio").inputStream.readBytes())
        val r = get("/audio", "bytes=1000-1999")
        assertEquals(206, r.responseCode)
        assertArrayEquals(bytes.copyOfRange(1000, 2000), r.inputStream.readBytes())
    }

    @Test fun introIsServedOnlyWhenSet() {
        assertEquals(404, get("/intro").responseCode)
        server.intro = movie
        val r = get("/intro", "bytes=0-99")
        assertEquals(206, r.responseCode)
        assertArrayEquals(bytes.copyOfRange(0, 100), r.inputStream.readBytes())
    }

    @Test fun unknownPathIs404() = assertEquals(404, get("/nope").responseCode)

    @Test fun noMovieIs404() {
        server.media = null
        assertEquals(404, get("/media").responseCode)
    }

    /** End-to-end: a listener syncs its clock to the host over real HTTP and predicts the picture position. */
    @Test fun listenerClockSyncOverHttp() {
        val hostOffset = 7_000L // host clock is 7 s ahead of the listener's clock
        var listenerNow = 1_000L
        val cs = ClockSync()
        for (seq in 0 until 8) {
            now = listenerNow + hostOffset + 2
            val t0 = listenerNow
            val pong = SyncCodec.decode(get("/clock?seq=$seq&t0=$t0&id=l1").inputStream.readBytes().decodeToString()).payload as Payload.ClockPong
            listenerNow += 4
            assertTrue(cs.addExchange(pong, listenerNow))
            listenerNow += 100
        }
        assertEquals(hostOffset.toDouble(), cs.offsetMs()!!, 2.0)
        state = Payload.PlaybackState(Payload.PlaybackState.State.PLAYING, 600_000, now, 1.0)
        val s = SyncCodec.decode(get("/state").inputStream.readBytes().decodeToString()).payload as Payload.PlaybackState
        // One second after the listener's "now", the picture should be 1 s plus the time since the state was sampled.
        val listenerLater = listenerNow + 1_000
        val expected = 600_000 + (listenerLater + hostOffset - s.hostTimeMs)
        val target = ListenerTarget.targetMs(s, cs.localToHost(listenerLater)!!, soundDelayMs = 0)
        assertEquals(expected.toDouble(), target.toDouble(), 5.0)
    }
}

class LocalNetworkTest {
    private fun ip(s: String) = java.net.InetAddress.getByName(s)

    @Test fun allowsHomeNetworks() {
        for (a in listOf("127.0.0.1", "192.168.1.23", "10.0.0.5", "172.16.4.2", "169.254.1.1", "::1", "fe80::1", "fd12:3456::1")) {
            assertTrue(a, MediaServer.isLocalNetwork(ip(a)))
        }
    }

    @Test fun refusesInternetAddresses() {
        for (a in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "2001:4860:4860::8888")) {
            assertTrue(a, !MediaServer.isLocalNetwork(ip(a)))
        }
    }
}

class HttpRangeTest {
    @Test fun parses() {
        assertEquals(HttpRange.Full, HttpRange.parse(null, 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRange.parse("bytes=0-", 100))
        assertEquals(HttpRange.Partial(10, 99), HttpRange.parse("bytes=10-500", 100))
        assertEquals(HttpRange.Partial(90, 99), HttpRange.parse("bytes=-10", 100))
        assertEquals(HttpRange.Partial(0, 99), HttpRange.parse("bytes=-1000", 100))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=100-", 100))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=50-10", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("bytes=0-1,5-6", 100))
        assertEquals(HttpRange.Full, HttpRange.parse("items=0-1", 100))
    }
}
