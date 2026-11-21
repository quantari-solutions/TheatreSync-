package com.theatresync.core

import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Stops whatever app is showing on a Chromecast over a fresh connection of its own: asks the TV what's
 * running, then tells it to stop that session. Used by the notification's Stop button, which must work
 * even when TheatreSync isn't watching the TV at that moment (in the background, or just restarted).
 * Blocking; call off the main thread. Returns true if the TV was told to stop something.
 */
object CastStopper {
    private const val SENDER = "sender-ts-stop"

    fun stop(connect: () -> Socket, timeoutMs: Long = 6_000): Boolean {
        connect().use { s ->
            s.soTimeout = 800
            val out = s.getOutputStream(); val input = s.getInputStream()
            fun send(ns: String, payload: String) = CastMessage.write(out, CastMessage(SENDER, "receiver-0", ns, payload))
            send(CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
            send(CastMessage.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val m = try { CastMessage.read(input) } catch (_: SocketTimeoutException) { continue }
                if (m.namespace == CastMessage.NS_HEARTBEAT && CastStatus.type(m.payloadUtf8) == "PING") {
                    CastMessage.write(out, CastMessage(SENDER, m.sourceId, CastMessage.NS_HEARTBEAT, """{"type":"PONG"}"""))
                    continue
                }
                if (m.namespace != CastMessage.NS_RECEIVER) continue
                val session = CastStatus.parseReceiver(m.payloadUtf8)?.sessionId ?: return false // nothing running
                send(CastMessage.NS_RECEIVER, """{"type":"STOP","sessionId":"$session","requestId":2}""")
                return true
            }
            return false
        }
    }
}
