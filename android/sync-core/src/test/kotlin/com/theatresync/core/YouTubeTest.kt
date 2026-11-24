package com.theatresync.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

class YouTubeLinksTest {
    @Test fun findsIdInEveryCommonLinkShape() {
        val id = "dQw4w9WgXcQ"
        for (text in listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?feature=share&v=$id&t=42",
            "https://youtu.be/$id?si=abc123",
            "https://youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id",
            "https://music.youtube.com/watch?v=$id&list=RD",
            "Check this out! https://youtu.be/$id",
            id,
        )) assertEquals(text, id, YouTubeLinks.videoId(text))
    }

    @Test fun rejectsNonVideos() {
        for (text in listOf(null, "", "hello", "https://www.youtube.com/", "https://example.com/watch?v=dQw4w9WgXcQ", "short123"))
            assertNull(text.toString(), YouTubeLinks.videoId(text))
    }
}

class YouTubeLoungeTest {
    private var http: HttpServer? = null
    @After fun stop() { http?.stop(0) }

    @Test
    fun followsTheLoungeProtocol() {
        val requests = CopyOnWriteArrayList<Triple<String, Map<String, String>, String?>>() // path?query, form, token
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http = it }
        fun form(s: String) = s.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += Triple(ex.requestURI.toString(), form(body), ex.requestHeaders.getFirst("X-YouTube-LoungeId-Token"))
            val reply = when {
                ex.requestURI.path.endsWith("get_lounge_token_batch") ->
                    """{"screens":[{"screenId":"scr-1","loungeToken":"LT-123","expiration":1}]}"""
                ex.requestURI.query?.contains("SID=") == true -> "ok"
                else -> """213
[[0,["c","SID-abc","",8]
],[1,["S","GS-xyz"]]]"""
            }.toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()

        YouTubeLounge("http://127.0.0.1:${server.address.port}/").play("scr-1", "dQw4w9WgXcQ")

        assertEquals(3, requests.size)
        val (tokenPath, tokenForm, _) = requests[0]
        assertTrue(tokenPath.endsWith("get_lounge_token_batch"))
        assertEquals("scr-1", tokenForm["screen_ids"])

        val (bindPath, bindForm, bindToken) = requests[1]
        assertTrue(bindPath, bindPath.contains("bc/bind?RID=0&VER=8&CVER=1"))
        assertEquals("LT-123", bindToken)
        assertEquals("REMOTE_CONTROL", bindForm["device"])

        val (playPath, playForm, playToken) = requests[2]
        assertTrue(playPath, playPath.contains("SID=SID-abc") && playPath.contains("gsessionid=GS-xyz") && playPath.contains("RID=1"))
        assertEquals("LT-123", playToken)
        assertEquals("setPlaylist", playForm["req0__sc"])
        assertEquals("dQw4w9WgXcQ", playForm["req0_videoId"])
        assertEquals("1", playForm["count"])
    }
}

class YouTubeScreenFinderTest {
    @Test(timeout = 15_000)
    fun launchesYouTubeAndReadsScreenId() {
        val server = ServerSocket(0)
        val seen = CopyOnWriteArrayList<CastMessage>()
        Thread {
            server.accept().use { c ->
                val out = c.getOutputStream()
                var launched = false
                while (true) {
                    val m = try { CastMessage.read(c.getInputStream()) } catch (_: Exception) { break }
                    seen += m
                    val type = CastStatus.type(m.payloadUtf8)
                    when {
                        type == "LAUNCH" -> launched = true
                        m.namespace == CastMessage.NS_RECEIVER && type == "GET_STATUS" && launched ->
                            CastMessage.write(out, CastMessage("receiver-0", m.sourceId, CastMessage.NS_RECEIVER,
                                """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"233637DE","displayName":"YouTube","isIdleScreen":false,
                                "namespaces":[{"name":"urn:x-cast:com.google.youtube.mdx"}],"transportId":"yt-7"}]}}"""))
                        m.namespace == YouTubeScreenFinder.NS_MDX ->
                            CastMessage.write(out, CastMessage("yt-7", m.sourceId, YouTubeScreenFinder.NS_MDX,
                                """{"type":"mdxSessionStatus","data":{"screenId":"SCREEN-42","deviceId":"d"}}"""))
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            val id = YouTubeScreenFinder({ Socket("127.0.0.1", server.localPort) }, timeoutMs = 10_000).screenId()
            assertEquals("SCREEN-42", id)
            assertTrue(seen.any { CastStatus.type(it.payloadUtf8) == "LAUNCH" && it.payloadUtf8.contains("233637DE") })
            assertTrue(seen.any { it.destinationId == "yt-7" && it.namespace == CastMessage.NS_CONNECTION })
        } finally {
            server.close()
        }
    }

    @Test fun parsesScreenId() {
        assertEquals("abc", YouTubeScreenFinder.parseScreenId("""{"type":"mdxSessionStatus","data":{"screenId":"abc"}}"""))
        assertNull(YouTubeScreenFinder.parseScreenId("""{"type":"other","data":{"screenId":"abc"}}"""))
    }

    @Test fun knowsWhetherTheTvIsListening() {
        val on = """[[0,["c","SID","",8]],[1,["S","GS"]],[2,["loungeStatus",{"devices":"[{\\"type\\":\\"LOUNGE_SCREEN\\",\\"name\\":\\"Media Room TV\\"},{\\"type\\":\\"REMOTE_CONTROL\\"}]"}]]]"""
        val off = """[[2,["loungeStatus",{"devices":"[{\\"type\\":\\"REMOTE_CONTROL\\"}]"}]]]"""
        org.junit.Assert.assertEquals(true, YouTubeLounge.screenListening(on))
        org.junit.Assert.assertEquals(false, YouTubeLounge.screenListening(off))
        org.junit.Assert.assertNull(YouTubeLounge.screenListening("[[0,[\"c\",\"SID\"]]]"))
    }
}
