package com.theatresync.core

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Plays a short clip (the Signature Intro) on a Chromecast through Google's Default Media
 * Receiver and returns when it has finished, so YouTube or screen mirroring can start right
 * after it. Uses the raw Cast v2 connection, like [YouTubeScreenFinder].
 */
class CastIntro(private val connect: () -> Socket) {
    /**
     * @param url where the TV fetches the clip (the phone's own little server)
     * @param maxMs give up waiting after this long (the clip is ~4 s)
     * @return true if the TV played it to the end (or at least started it before [maxMs])
     */
    fun play(url: String, maxMs: Long = 15_000, liveMs: Long? = null): Boolean {
        connect().use { s ->
            s.soTimeout = 500
            val out = s.getOutputStream()
            val input = s.getInputStream()
            fun send(dest: String, ns: String, payload: String) = CastMessage.write(out, CastMessage(SENDER, dest, ns, payload))
            send("receiver-0", CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
            send("receiver-0", CastMessage.NS_RECEIVER, """{"type":"LAUNCH","appId":"${CastStatus.APP_DEFAULT_MEDIA_RECEIVER}","requestId":1}""")
            val deadline = System.currentTimeMillis() + maxMs
            var transport: String? = null
            var loaded = false
            var started = false
            val heard = ArrayList<String>() // what the TV said, for the problem report if it never plays
            while (System.currentTimeMillis() < deadline) {
                val m = try { CastMessage.read(input) } catch (_: SocketTimeoutException) { continue }
                val payload = m.payloadUtf8
                when {
                    m.namespace == CastMessage.NS_HEARTBEAT && CastStatus.type(payload) == "PING" ->
                        send(m.sourceId, CastMessage.NS_HEARTBEAT, """{"type":"PONG"}""")
                    m.namespace == CastMessage.NS_RECEIVER && transport == null -> {
                        val app = CastStatus.parseReceiver(payload)
                        if (app?.appId == CastStatus.APP_DEFAULT_MEDIA_RECEIVER && app.transportId != null) {
                            transport = app.transportId
                            send(app.transportId, CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
                            send(app.transportId, CastMessage.NS_MEDIA, loadRequest(url, live = liveMs != null))
                            loaded = true
                        }
                    }
                    m.namespace == CastMessage.NS_MEDIA && loaded -> when (playerState(payload).also { st -> if (heard.size < 12) heard += (st ?: CastStatus.type(payload) ?: "?") + idleReason(payload) }) {
                        "PLAYING" -> {
                            started = true
                            // A live intro never "finishes": let it play for its length, then hand over.
                            if (liveMs != null) { Thread.sleep(liveMs); return true }
                        }
                        "BUFFERING" -> started = true
                        "IDLE" -> if (started || payload.contains("\"FINISHED\"")) return true
                        "ERROR" -> throw IOException("TV couldn't play the intro")
                    }
                    m.namespace == CastMessage.NS_MEDIA && payload.contains("LOAD_FAILED") ->
                        throw IOException("TV couldn't load the intro")
                }
            }
            if (!started) throw IOException("TV never started the intro (transport=${transport != null}, heard: ${heard.joinToString().ifBlank { "nothing" }})")
            return true
        }
    }

    companion object {
        private const val SENDER = "sender-ts-intro"

        /**
         * [live]: the intro video marked as live, so the TV shows no progress bar or time over it (a normal
         * video gets those and looks like an advert). Same MP4 the TV plays fine as a normal video; HLS and
         * animated pictures are unproven on the owner's Chromecast (0.5.9 / 0.6.0 showed nothing).
         */
        fun loadRequest(url: String, live: Boolean = false): String =
            if (live) """{"type":"LOAD","requestId":2,"autoplay":true,""" +
                """"media":{"contentId":"$url","contentType":"video/mp4","streamType":"LIVE","metadata":{"metadataType":0}}}"""
            else """{"type":"LOAD","requestId":2,"autoplay":true,"currentTime":0,""" +
                """"media":{"contentId":"$url","contentType":"video/mp4","streamType":"BUFFERED",""" +
                """"metadata":{"metadataType":0,"title":"TheatreSync"}}}"""

        /** "PLAYING", "IDLE"… from a MEDIA_STATUS message, or null. */
        private fun idleReason(payload: String): String =
            Regex(""""idleReason"\s*:\s*"([A-Z]+)"""").find(payload)?.groupValues?.get(1)?.let { "($it)" } ?: ""

        fun playerState(payload: String): String? =
            Regex(""""playerState"\s*:\s*"([A-Z]+)"""").find(payload)?.groupValues?.get(1)
    }
}
