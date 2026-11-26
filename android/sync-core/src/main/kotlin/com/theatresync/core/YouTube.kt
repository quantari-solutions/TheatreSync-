package com.theatresync.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** Finds the 11-character video ID in anything the user pastes or shares from YouTube. */
object YouTubeLinks {
    private val ID = "[A-Za-z0-9_-]{11}"
    private val patterns = listOf(
        Regex("""(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?(?:[^#\s]*&)?v=|shorts/|live/|embed/|v/)($ID)"""),
        Regex("""youtu\.be/($ID)"""),
        Regex("""music\.youtube\.com/watch\?(?:[^#\s]*&)?v=($ID)"""),
    )

    fun videoId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (p in patterns) p.find(text)?.let { return it.groupValues[1] }
        val bare = text.trim()
        return if (Regex("^$ID$").matches(bare)) bare else null
    }
}

/**
 * Starts the YouTube app on a Chromecast and asks it for its "screen ID" over the
 * Cast v2 connection (namespace urn:x-cast:com.google.youtube.mdx), as pychromecast does.
 */
class YouTubeScreenFinder(private val connect: () -> Socket, private val timeoutMs: Long = 25_000) {
    fun screenId(): String {
        connect().use { s ->
            s.soTimeout = 1_000
            val out = s.getOutputStream()
            val input = s.getInputStream()
            fun send(dest: String, ns: String, payload: String) = CastMessage.write(out, CastMessage(SENDER, dest, ns, payload))
            send("receiver-0", CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
            send("receiver-0", CastMessage.NS_RECEIVER, """{"type":"LAUNCH","appId":"${CastStatus.APP_YOUTUBE}","requestId":1}""")
            val deadline = System.currentTimeMillis() + timeoutMs
            var transport: String? = null
            var lastAsk = 0L
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (transport != null && now - lastAsk > 2_000) {
                    send(transport, NS_MDX, """{"type":"getMdxSessionStatus"}""")
                    lastAsk = now
                } else if (transport == null && now - lastAsk > 2_000) {
                    send("receiver-0", CastMessage.NS_RECEIVER, """{"type":"GET_STATUS","requestId":2}""")
                    lastAsk = now
                }
                val m = try { CastMessage.read(input) } catch (_: SocketTimeoutException) { continue }
                when {
                    m.namespace == CastMessage.NS_HEARTBEAT && CastStatus.type(m.payloadUtf8) == "PING" ->
                        send(m.sourceId, CastMessage.NS_HEARTBEAT, """{"type":"PONG"}""")
                    m.namespace == CastMessage.NS_RECEIVER && transport == null -> {
                        val app = CastStatus.parseReceiver(m.payloadUtf8)
                        if (app?.appId == CastStatus.APP_YOUTUBE && app.transportId != null) {
                            transport = app.transportId
                            send(app.transportId, CastMessage.NS_CONNECTION, """{"type":"CONNECT"}""")
                            send(app.transportId, NS_MDX, """{"type":"getMdxSessionStatus"}""")
                            lastAsk = System.currentTimeMillis()
                        }
                    }
                    m.namespace == NS_MDX -> parseScreenId(m.payloadUtf8)?.let { return it }
                }
            }
            throw IOException("YouTube didn't start on the TV")
        }
    }

    companion object {
        const val NS_MDX = "urn:x-cast:com.google.youtube.mdx"
        private const val SENDER = "sender-ts-yt"

        fun parseScreenId(payload: String): String? =
            Regex(""""type"\s*:\s*"mdxSessionStatus"""").find(payload)?.let {
                Regex(""""screenId"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
            }
    }
}

/**
 * Tells the YouTube app on the TV which video to play, using YouTube's "Lounge"
 * remote-control API (the same calls as the open-source casttube library).
 * Undocumented by Google: if it changes, callers fall back to the YouTube app.
 */
class YouTubeLounge(private val baseUrl: String = "https://www.youtube.com/") {
    /** @param screenListening whether the TV's YouTube is connected to the link right now (from "loungeStatus"):
     *  false means it isn't open on the TV, so nothing sent will play; null when YouTube didn't say. */
    data class Session(val loungeToken: String, val sid: String, val gsessionId: String, val screenListening: Boolean? = null)

    private var rid = 0
    private var reqCount = 0

    fun play(screenId: String, videoId: String) {
        val token = loungeToken(screenId)
        val session = bind(token)
        setPlaylist(session, videoId)
    }

    fun loungeToken(screenId: String): String {
        val body = post("api/lounge/pairing/get_lounge_token_batch", emptyMap(), mapOf("screen_ids" to screenId), null)
        return Regex(""""loungeToken"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: throw IOException("no lounge token")
    }

    fun bind(token: String): Session {
        rid = 0
        reqCount = 0
        val body = post("api/lounge/bc/bind", mapOf("RID" to "$rid", "VER" to "8", "CVER" to "1"), BIND_DATA, token)
        val sid = Regex(""""c","(.*?)",""").find(body)?.groupValues?.get(1) ?: throw IOException("no SID")
        val gsid = Regex(""""S","(.*?)"]""").find(body)?.groupValues?.get(1) ?: throw IOException("no gsessionid")
        return Session(token, sid, gsid, screenListening(body))
    }

    fun setPlaylist(s: Session, videoId: String) {
        rid++
        val prefix = "req$reqCount"
        val data = linkedMapOf(
            "count" to "1",
            "${prefix}__sc" to "setPlaylist",
            "${prefix}_videoId" to videoId,
            "${prefix}_currentTime" to "0",
            "${prefix}_currentIndex" to "-1",
            "${prefix}_audioOnly" to "false",
            "${prefix}_listId" to "",
        )
        reqCount++
        post(
            "api/lounge/bc/bind",
            mapOf("SID" to s.sid, "gsessionid" to s.gsessionId, "RID" to "$rid", "VER" to "8", "CVER" to "1"),
            data, s.loungeToken,
        )
    }

    /**
     * Sends a remote-control command to the TV's YouTube app, e.g. "next", "previous",
     * "setSubtitlesTrack" (videoId + languageCode; empty languageCode = captions off).
     */
    fun command(s: Session, name: String, params: Map<String, String> = emptyMap()) {
        rid++
        val prefix = "req$reqCount"
        val data = linkedMapOf("count" to "1", "${prefix}__sc" to name)
        params.forEach { (k, v) -> data["${prefix}_$k"] = v }
        reqCount++
        post(
            "api/lounge/bc/bind",
            mapOf("SID" to s.sid, "gsessionid" to s.gsessionId, "RID" to "$rid", "VER" to "8", "CVER" to "1"),
            data, s.loungeToken,
        )
    }

    /**
     * Reads the TV's recent remote-control events (queue and "up next"). Long-poll style: returns
     * whatever arrived within [waitMs]. Blocking; call off the main thread.
     */
    fun events(s: Session, waitMs: Int = 4_000, afterId: Long = 0): String {
        val query = encode(mapOf(
            "SID" to s.sid, "gsessionid" to s.gsessionId, "RID" to "rpc", "VER" to "8", "CVER" to "1",
            "TYPE" to "xmlhttp", "CI" to "0", "AID" to "$afterId",
        ))
        val c = URL(baseUrl + "api/lounge/bc/bind?" + query).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = waitMs
        c.setRequestProperty("Origin", baseUrl)
        c.setRequestProperty("X-YouTube-LoungeId-Token", s.loungeToken)
        val buf = StringBuilder()
        try {
            c.inputStream.bufferedReader().use { r ->
                val chunk = CharArray(4096)
                while (true) { val n = r.read(chunk); if (n < 0) break; buf.append(chunk, 0, n) }
            }
        } catch (_: java.net.SocketTimeoutException) {
            // long poll: keep what arrived
        } finally {
            c.disconnect()
        }
        return buf.toString()
    }

    private fun post(path: String, params: Map<String, String>, form: Map<String, String>, token: String?): String {
        val query = if (params.isEmpty()) "" else "?" + encode(params)
        val c = URL(baseUrl + path + query).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.doOutput = true
        c.setRequestProperty("Origin", baseUrl)
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        token?.let { c.setRequestProperty("X-YouTube-LoungeId-Token", it) }
        c.outputStream.use { it.write(encode(form).toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        if (code !in 200..299) throw IOException("YouTube answered $code")
        return text
    }

    private fun encode(m: Map<String, String>) =
        m.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    companion object {
        /** From a bind reply: is a TV ("LOUNGE_SCREEN") connected to this link? Null if YouTube sent no status. */
        fun screenListening(bindBody: String): Boolean? {
            if (!bindBody.contains("loungeStatus")) return null
            return bindBody.contains("LOUNGE_SCREEN")
        }

        private val BIND_DATA = linkedMapOf(
            "device" to "REMOTE_CONTROL",
            "id" to "theatresyncremote00000000",
            "name" to "TheatreSync",
            "mdx-version" to "3",
            "pairing_type" to "cast",
            "app" to "android-phone-13.14.55",
            // Queue, ad and playback events (needed for Up next and the ad skipper).
            "capabilities" to "que,dsdtr,atp,vsp",
        )
    }
}

/** What's coming up on the TV's YouTube, parsed from Lounge events. Pure, unit-tested. */
object UpNext {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Video IDs after the current one (playlist or Mix), then YouTube's autoplay pick; newest event wins. */
    fun parse(events: String, limit: Int = 10): List<String> {
        val playlist = Regex(""""videoIds"\s*:\s*"([^"]+)"""").findAll(events).lastOrNull()?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.filter { ID.matches(it) }.orEmpty()
        val index = Regex(""""currentIndex"\s*:\s*"?(\d+)""").findAll(events).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val after = if (index in playlist.indices) playlist.drop(index + 1) else playlist
        val autoplay = Regex(""""autoplayUpNext"\s*,\s*\{[^}]*"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""").findAll(events).lastOrNull()?.groupValues?.get(1)
        return (after + listOfNotNull(autoplay)).distinct().take(limit)
    }

    /** The playlist / Mix ID, if the TV is playing one (needed to jump within it). */
    fun listId(events: String): String? =
        Regex(""""listId"\s*:\s*"([^"]+)"""").findAll(events).lastOrNull()?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

/** Lounge event stream → events. Pure, unit-tested. */
object LoungeEvents {
    data class Event(val id: Long, val name: String, val fields: Map<String, String>)

    private val EVENT = Regex("""\[(\d+),\["([A-Za-z]+)"(?:,(\{[^\[\]]*?\}))?\]\]""")
    private val FIELD = Regex(""""([A-Za-z]+)"\s*:\s*"([^"]*)"""")

    fun parse(text: String): List<Event> = EVENT.findAll(text).map { m ->
        val fields = m.groupValues[3].let { body -> FIELD.findAll(body).associate { it.groupValues[1] to it.groupValues[2] } }
        Event(m.groupValues[1].toLong(), m.groupValues[2], fields)
    }.toList()
}

/**
 * SponsorBlock (sponsor.ajay.app): community-marked segments (sponsors, self-promotion,
 * "like and subscribe") to skip. The video ID is sent only as the first 4 hex characters of its
 * SHA-256, as SponsorBlock recommends for privacy. Parsing is pure and unit-tested.
 */
object SponsorBlock {
    data class Segment(val startSec: Double, val endSec: Double, val category: String)

    val DEFAULT_CATEGORIES = listOf("sponsor", "selfpromo", "interaction")

    fun hashPrefix(videoId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)

    fun url(videoId: String, categories: List<String> = DEFAULT_CATEGORIES): String =
        "https://sponsor.ajay.app/api/skipSegments/${hashPrefix(videoId)}?service=YouTube&actionType=skip" +
            categories.joinToString("") { "&category=$it" }

    /** Segments for [videoId] from the hashed-prefix response (which lists several videos). */
    fun parse(json: String, videoId: String): List<Segment> {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val entry = root.firstOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("videoID")?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content } == videoId }
            as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val segments = entry["segments"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return segments.mapNotNull { s ->
            val o = s as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val pair = o["segment"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val start = (pair.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val end = (pair.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val category = (o["category"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "sponsor"
            if (end - start >= 1.0) Segment(start, end, category) else null
        }.sortedBy { it.startSec }
    }

    /** The segment [positionSec] is inside (skip to its end), if any. */
    fun current(segments: List<Segment>, positionSec: Double): Segment? =
        segments.firstOrNull { positionSec >= it.startSec && positionSec < it.endSec - 0.5 }
}
