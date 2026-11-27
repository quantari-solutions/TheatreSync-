package com.theatresync.core

import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * The Frame (Samsung TV) remote over the home Wi-Fi, inside TheatreSync: the TV's own remote-control
 * channel (WebSocket on port 8001, the one Samsung's phone remotes use). The first time, the TV asks
 * "Allow TheatreSync?" on screen; after that keys go straight through. Power on uses Wake-on-LAN.
 */
object SamsungRemote {
    enum class Result { OK, ALLOW_ON_TV, FAILED }

    /** Remote keys, named as the TV expects. */
    object Keys {
        const val POWER = "KEY_POWER"; const val VOL_UP = "KEY_VOLUP"; const val VOL_DOWN = "KEY_VOLDOWN"
        const val MUTE = "KEY_MUTE"; const val HOME = "KEY_HOME"; const val BACK = "KEY_RETURN"; const val ENTER = "KEY_ENTER"
        const val UP = "KEY_UP"; const val DOWN = "KEY_DOWN"; const val LEFT = "KEY_LEFT"; const val RIGHT = "KEY_RIGHT"
        const val PLAY_PAUSE = "KEY_PLAY_BACK"
    }

    /** Samsung's app number for YouTube on its TVs. */
    const val YOUTUBE_APP = "111299001912"
    /** Netflix and Prime Video on Samsung TVs (newer, then older app numbers). */
    val NETFLIX_APPS = listOf("3201907018807", "11101200001")
    val PRIME_APPS = listOf("3201910019365", "3201512006785")

    fun launchJson(appId: String) =
        """{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host","data":{"appId":"$appId","action_type":"DEEP_LINK"}}}"""

    fun clickJson(key: String) =
        """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$key","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""

    fun channelPath(appName: String = "TheatreSync") =
        "/api/v2/channels/samsung.remote.control?name=" + Base64.getEncoder().encodeToString(appName.toByteArray())

    /** Sends [keys] to the TV at [ip] (blocking; a few seconds at most, longer the first time while the TV asks). */
    fun send(ip: String, vararg keys: String, waitForAllowMs: Int = 20_000): Result = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 8001), 2_000)
            s.soTimeout = waitForAllowMs
            val out = s.getOutputStream(); val inp = s.getInputStream()
            val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            out.write(("GET ${channelPath()} HTTP/1.1\r\nHost: $ip:8001\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            out.flush()
            val head = readHeaders(inp)
            if (!head.startsWith("HTTP/1.1 101")) return Result.FAILED
            // The TV says "ms.channel.connect" once allowed, or "ms.channel.unauthorized" if refused.
            val hello = readTextFrame(inp) ?: return Result.ALLOW_ON_TV
            if ("unauthorized" in hello || "ms.channel.connect" !in hello) return Result.ALLOW_ON_TV
            for (k in keys) { out.write(textFrame(if (k.startsWith("APP:")) launchJson(k.removePrefix("APP:")) else clickJson(k))); out.flush(); Thread.sleep(120) }
            Result.OK
        }
    }.getOrElse { if (it is java.net.SocketTimeoutException) Result.ALLOW_ON_TV else Result.FAILED }

    /** Wake-on-LAN magic packet for [mac] ("aa:bb:cc:dd:ee:ff"): switches the TV on from standby. */
    fun wake(mac: String, broadcast: String = "255.255.255.255") = runCatching {
        DatagramSocket().use { s ->
            s.broadcast = true
            val p = magicPacket(mac)
            for (port in intArrayOf(9, 7)) s.send(DatagramPacket(p, p.size, InetAddress.getByName(broadcast), port))
        }
    }.isSuccess

    fun magicPacket(mac: String): ByteArray {
        val m = mac.split(':', '-').map { it.toInt(16).toByte() }
        require(m.size == 6) { "bad MAC" }
        return ByteArray(6) { 0xFF.toByte() } + (0 until 16).flatMap { m }.toByteArray()
    }

    /** A masked client text frame (RFC 6455). */
    fun textFrame(text: String, mask: ByteArray = ByteArray(4).also { SecureRandom().nextBytes(it) }): ByteArray {
        val data = text.toByteArray()
        val head = java.io.ByteArrayOutputStream()
        head.write(0x81)
        when {
            data.size < 126 -> head.write(0x80 or data.size)
            data.size < 65536 -> { head.write(0x80 or 126); head.write(data.size shr 8); head.write(data.size and 0xFF) }
            else -> error("frame too large")
        }
        head.write(mask)
        head.write(ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() })
        return head.toByteArray()
    }

    /** Reads one (unmasked, server) text frame; null at end of stream. */
    fun readTextFrame(inp: InputStream): String? {
        val b0 = inp.read(); if (b0 < 0) return null
        var len = inp.read() and 0x7F
        if (len == 126) len = (inp.read() shl 8) or inp.read()
        else if (len == 127) { var l = 0L; repeat(8) { l = (l shl 8) or inp.read().toLong() }; len = l.toInt() }
        val buf = ByteArray(len); var got = 0
        while (got < len) { val n = inp.read(buf, got, len - got); if (n < 0) return null; got += n }
        return String(buf)
    }

    private fun readHeaders(inp: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) break; sb.append(c.toChar()) }
        return sb.toString()
    }
}
