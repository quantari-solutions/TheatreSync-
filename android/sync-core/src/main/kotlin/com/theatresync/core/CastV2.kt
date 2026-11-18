package com.theatresync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal Google Cast v2 wire protocol: the length-prefixed protobuf `CastMessage`
 * every Chromecast speaks on TCP 8009 (TLS). Used read-mostly, to see what the TV
 * is showing (YouTube, a mirrored Chrome tab, …) even when another device started it,
 * the same approach as open-source clients such as pychromecast.
 *
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1;  // CASTV2_1_0 = 0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5;          // STRING = 0, BINARY = 1
 *   optional string payload_utf8 = 6;
 *   optional bytes payload_binary = 7;
 * }
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
) {
    companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val MAX_FRAME = 64 * 1024

        fun encode(m: CastMessage): ByteArray {
            val body = ByteArrayOutputStream()
            fun varint(v: Long) {
                var x = v
                while (true) {
                    if (x and 0x7FL.inv() == 0L) { body.write(x.toInt()); return }
                    body.write(((x and 0x7F) or 0x80).toInt())
                    x = x ushr 7
                }
            }
            fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
            fun str(field: Int, s: String) {
                val b = s.toByteArray(Charsets.UTF_8)
                tag(field, 2); varint(b.size.toLong()); body.write(b)
            }
            tag(1, 0); varint(0)
            str(2, m.sourceId)
            str(3, m.destinationId)
            str(4, m.namespace)
            tag(5, 0); varint(0)
            str(6, m.payloadUtf8)
            return body.toByteArray()
        }

        fun decode(bytes: ByteArray): CastMessage {
            var i = 0
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    if (i >= bytes.size) throw IOException("truncated varint")
                    val b = bytes[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("varint too long")
                }
            }
            var source = ""
            var dest = ""
            var ns = ""
            var payload = ""
            while (i < bytes.size) {
                val key = varint().toInt()
                val field = key ushr 3
                when (val wire = key and 7) {
                    0 -> varint()
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || i + len > bytes.size) throw IOException("bad length")
                        val s = String(bytes, i, len, Charsets.UTF_8)
                        i += len
                        when (field) {
                            2 -> source = s
                            3 -> dest = s
                            4 -> ns = s
                            6 -> payload = s
                        }
                    }
                    else -> throw IOException("unsupported wire type $wire")
                }
            }
            return CastMessage(source, dest, ns, payload)
        }

        /** Writes one frame: 4-byte big-endian length, then the protobuf. */
        fun write(out: OutputStream, m: CastMessage) {
            val b = encode(m)
            out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            out.write(b)
            out.flush()
        }

        /** Reads one frame (blocking). */
        fun read(input: InputStream): CastMessage {
            val din = DataInputStream(input)
            val len = try { din.readInt() } catch (e: EOFException) { throw IOException("connection closed", e) }
            if (len <= 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
            val b = ByteArray(len)
            din.readFully(b)
            return decode(b)
        }
    }
}

/** What a Chromecast is showing right now, as far as the Cast protocol tells us. */
data class CastActivity(
    val appId: String,
    val appName: String,
    val kind: Kind,
    val statusText: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val playerState: String? = null,
    val currentTimeSec: Double? = null,
    val durationSec: Double? = null,
    val mediaSessionId: Int? = null,
    val transportId: String? = null,
    val hasMedia: Boolean = false,
) {
    enum class Kind { YOUTUBE, MIRRORING, THEATRESYNC, MEDIA, OTHER }

    val isPlaying: Boolean get() = playerState == "PLAYING" || playerState == "BUFFERING"
    val canControl: Boolean get() = mediaSessionId != null && transportId != null
}

/** Parses Cast receiver/media status JSON into [CastActivity]. Pure functions, unit-tested. */
object CastStatus {
    const val APP_YOUTUBE = "233637DE"
    const val APP_DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    const val APP_BACKDROP = "E8C28D3C"
    private val MIRRORING_APPS = setOf("0F5096E8", "674A0243", "85CDB22F", "8E6C866D")

    private val json = Json { ignoreUnknownKeys = true }

    fun type(payload: String): String? = try {
        json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull()
    } catch (_: Exception) { null }

    /** From RECEIVER_STATUS: the app in the foreground, or null if the TV is idle (ambient/backdrop). */
    fun parseReceiver(payload: String): CastActivity? {
        val root = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return null }
        val apps = (root["status"] as? JsonObject)?.get("applications") as? JsonArray ?: return null
        val app = apps.mapNotNull { it as? JsonObject }.firstOrNull { a ->
            val id = a.str("appId")
            id != null && id != APP_BACKDROP && a.bool("isIdleScreen") != true
        } ?: return null
        val appId = app.str("appId") ?: return null
        val name = app.str("displayName") ?: "Cast"
        val namespaces = (app["namespaces"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty()
        val kind = when {
            appId == APP_YOUTUBE -> CastActivity.Kind.YOUTUBE
            appId in MIRRORING_APPS || name.contains("mirror", ignoreCase = true) -> CastActivity.Kind.MIRRORING
            namespaces.contains(CastMessage.NS_MEDIA) -> CastActivity.Kind.MEDIA
            else -> CastActivity.Kind.OTHER
        }
        return CastActivity(
            appId = appId,
            appName = name,
            kind = kind,
            statusText = app.str("statusText")?.takeIf { it.isNotBlank() },
            transportId = app.str("transportId"),
            hasMedia = namespaces.contains(CastMessage.NS_MEDIA),
        )
    }

    /** Merges a MEDIA_STATUS payload into [base]. Returns [base] unchanged if there's no media. */
    fun mergeMedia(base: CastActivity, payload: String): CastActivity {
        val root = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { return base }
        val status = (root["status"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return base
        val media = status["media"] as? JsonObject
        val meta = media?.get("metadata") as? JsonObject
        val contentId = media?.str("contentId")
        val image = (meta?.get("images") as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.str("url") }
            ?: if (base.kind == CastActivity.Kind.YOUTUBE && contentId != null && YOUTUBE_ID.matches(contentId)) {
                "https://i.ytimg.com/vi/$contentId/hqdefault.jpg"
            } else null
        return base.copy(
            title = meta?.str("title") ?: base.title,
            subtitle = meta?.str("subtitle") ?: meta?.str("artist") ?: meta?.str("seriesTitle") ?: base.subtitle,
            imageUrl = image ?: base.imageUrl,
            playerState = status.str("playerState") ?: base.playerState,
            currentTimeSec = status.num("currentTime") ?: base.currentTimeSec,
            durationSec = media?.num("duration")?.takeIf { it > 0 } ?: base.durationSec,
            mediaSessionId = status.int("mediaSessionId") ?: base.mediaSessionId,
        )
    }

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull()
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
    @Suppress("unused") private fun JsonElement.arr() = jsonArray
}
