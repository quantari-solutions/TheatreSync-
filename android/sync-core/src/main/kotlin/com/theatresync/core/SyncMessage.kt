package com.theatresync.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Protocol version implemented by this build. Must match contracts/schemas/sync-message.v1.schema.json. */
const val PROTOCOL_VERSION = 1

/** Wire envelope. Mirrors the JSON schema in /contracts exactly. */
@Serializable
data class Envelope(
    val v: Int,
    val type: String,
    val senderId: String,
    val sentAtMs: Long,
    val payload: JsonObject,
)

/** Typed payloads. [type] values are the schema's `type` enum. */
sealed interface Payload {
    @Serializable
    data class Hello(
        val deviceName: String,
        val platform: Platform,
        val role: Role,
        val appVersion: String,
        val protocolVersions: List<Int>,
    ) : Payload

    @Serializable
    data class ClockPing(val seq: Int, val t0: Long) : Payload

    @Serializable
    data class ClockPong(val seq: Int, val t0: Long, val t1: Long, val t2: Long) : Payload

    @Serializable
    data class PlaybackState(
        val state: State,
        val mediaPositionMs: Long,
        val hostTimeMs: Long,
        val playbackRate: Double,
        val mediaTitle: String? = null,
    ) : Payload {
        @Serializable
        enum class State {
            @SerialName("playing") PLAYING,
            @SerialName("paused") PAUSED,
            @SerialName("buffering") BUFFERING,
            @SerialName("stopped") STOPPED,
        }
    }

    @Serializable
    data class LatencyReport(
        val outputRoute: OutputRoute,
        val outputDeviceName: String? = null,
        val estimatedOutputLatencyMs: Int,
        val userOffsetMs: Int,
    ) : Payload

    @Serializable
    data class Error(val code: Code, val message: String) : Payload {
        @Serializable
        enum class Code {
            @SerialName("unsupported_version") UNSUPPORTED_VERSION,
            @SerialName("bad_message") BAD_MESSAGE,
            @SerialName("not_host") NOT_HOST,
            @SerialName("internal") INTERNAL,
        }
    }
}

@Serializable
enum class Platform { @SerialName("android") ANDROID, @SerialName("ios") IOS }

@Serializable
enum class Role { @SerialName("host") HOST, @SerialName("listener") LISTENER, @SerialName("remote") REMOTE }

@Serializable
enum class OutputRoute {
    @SerialName("bluetooth_a2dp") BLUETOOTH_A2DP,
    @SerialName("bluetooth_le_audio") BLUETOOTH_LE_AUDIO,
    @SerialName("wired") WIRED,
    @SerialName("speaker") SPEAKER,
    @SerialName("cast") CAST,
    @SerialName("unknown") UNKNOWN,
}

data class SyncMessage(val senderId: String, val sentAtMs: Long, val payload: Payload)

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encodes/decodes [SyncMessage]s to the v1 wire format. */
object SyncCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(message: SyncMessage): String {
        val p = message.payload
        val (type, body) = when (p) {
            is Payload.Hello -> "hello" to json.encodeToJsonElement(p)
            is Payload.ClockPing -> "clock_ping" to json.encodeToJsonElement(p)
            is Payload.ClockPong -> "clock_pong" to json.encodeToJsonElement(p)
            is Payload.PlaybackState -> "playback_state" to json.encodeToJsonElement(p)
            is Payload.LatencyReport -> "latency_report" to json.encodeToJsonElement(p)
            is Payload.Error -> "error" to json.encodeToJsonElement(p)
        }
        val env = Envelope(PROTOCOL_VERSION, type, message.senderId, message.sentAtMs, body as JsonObject)
        return json.encodeToString(Envelope.serializer(), env)
    }

    fun decode(text: String): SyncMessage {
        val env = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (e: Exception) {
            throw ProtocolException("Malformed envelope", e)
        }
        if (env.v != PROTOCOL_VERSION) throw ProtocolException("Unsupported protocol version ${env.v}")
        val payload: Payload = try {
            when (env.type) {
                "hello" -> json.decodeFromJsonElement<Payload.Hello>(env.payload)
                "clock_ping" -> json.decodeFromJsonElement<Payload.ClockPing>(env.payload)
                "clock_pong" -> json.decodeFromJsonElement<Payload.ClockPong>(env.payload)
                "playback_state" -> json.decodeFromJsonElement<Payload.PlaybackState>(env.payload)
                "latency_report" -> json.decodeFromJsonElement<Payload.LatencyReport>(env.payload)
                "error" -> json.decodeFromJsonElement<Payload.Error>(env.payload)
                else -> throw ProtocolException("Unknown message type '${env.type}'")
            }
        } catch (e: ProtocolException) {
            throw e
        } catch (e: Exception) {
            throw ProtocolException("Bad '${env.type}' payload: ${e.message}", e)
        }
        validate(payload)
        return SyncMessage(env.senderId, env.sentAtMs, payload)
    }

    /** Range checks the schema enforces that the type system cannot. */
    private fun validate(p: Payload) {
        fun req(ok: Boolean, msg: String) { if (!ok) throw ProtocolException(msg) }
        when (p) {
            is Payload.PlaybackState -> {
                req(p.mediaPositionMs >= 0, "mediaPositionMs must be >= 0")
                req(p.playbackRate > 0 && p.playbackRate <= 4, "playbackRate out of range")
            }
            is Payload.LatencyReport -> {
                req(p.estimatedOutputLatencyMs in 0..2000, "estimatedOutputLatencyMs out of range")
                req(p.userOffsetMs in -1000..1000, "userOffsetMs out of range")
            }
            is Payload.ClockPong -> req(p.t2 >= p.t1, "t2 must be >= t1")
            else -> Unit
        }
    }
}
