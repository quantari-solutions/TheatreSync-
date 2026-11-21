package com.theatresync.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class CastStopperTest {
    private val youtube = """{"requestId":1,"status":{"applications":[{"appId":"233637DE","displayName":"YouTube","isIdleScreen":false,
        "namespaces":[{"name":"urn:x-cast:com.google.youtube.mdx"}],"sessionId":"a1b2","statusText":"YouTube","transportId":"web-7"}]},"type":"RECEIVER_STATUS"}"""
    private val idle = """{"requestId":1,"status":{"applications":[{"appId":"E8C28D3C","displayName":"Backdrop","isIdleScreen":true,
        "namespaces":[],"sessionId":"b","statusText":"","transportId":"web-0"}]},"type":"RECEIVER_STATUS"}"""

    /** A pretend Chromecast: answers GET_STATUS with [status], records everything sent to it. */
    private fun fakeTv(status: String, got: MutableList<String>): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                runCatching {
                    while (true) {
                        val m = CastMessage.read(s.getInputStream())
                        got += m.payloadUtf8
                        if (m.payloadUtf8.contains("GET_STATUS")) CastMessage.write(s.getOutputStream(), CastMessage("receiver-0", m.sourceId, CastMessage.NS_RECEIVER, status))
                    }
                }
            }
        }
        return server
    }

    @Test fun stopsTheRunningAppOverItsOwnConnection() {
        val got = CopyOnWriteArrayList<String>()
        val tv = fakeTv(youtube, got)
        assertTrue(CastStopper.stop({ Socket("127.0.0.1", tv.localPort) }))
        Thread.sleep(200)
        assertTrue(got.any { it.contains("\"type\":\"STOP\"") && it.contains("a1b2") })
        tv.close()
    }

    @Test fun leavesTheIdleScreenAlone() {
        val got = CopyOnWriteArrayList<String>()
        val tv = fakeTv(idle, got)
        assertFalse(CastStopper.stop({ Socket("127.0.0.1", tv.localPort) }, timeoutMs = 2_000))
        assertTrue(got.none { it.contains("\"STOP\"") })
        tv.close()
    }
}
