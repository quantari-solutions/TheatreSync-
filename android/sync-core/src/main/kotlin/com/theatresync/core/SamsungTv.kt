package com.theatresync.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Samsung smart TVs (The Frame) answer http://<ip>:8001/api/v2/ with their name and type, even in Art
 * mode and when the Wi-Fi announcement (mDNS) or the DIAL search doesn't get through. Used to find them
 * by asking each address on the Wi-Fi, and to check an address typed in Settings.
 */
object SamsungTv {
    const val INFO_PORT = 8001

    data class Info(val name: String, val isFrame: Boolean, val mac: String? = null)

    /** The TV's name if the reply is from a Samsung TV (not a phone, soundbar or other Samsung device). */
    fun parse(json: String): Info? {
        val type = field(json, "type") ?: return null
        if (!type.contains("TV", ignoreCase = true)) return null
        val name = field(json, "name")?.removePrefix("[TV] ")?.trim()?.ifBlank { null } ?: "Samsung TV"
        val frame = field(json, "FrameTVSupport") == "true"
        return Info(name, frame, field(json, "wifiMac")?.takeIf { it.count { c -> c == ':' } == 5 })
    }

    /** The DIAL device for the TV at [ip], for YouTube casting. */
    fun device(ip: String, name: String) = Dial.Device(
        id = "samsung:$ip", name = name, appsUrl = "http://$ip:8080/ws/apps/", manufacturer = "Samsung", model = null,
    )

    /** Asks the TV at [ip] who it is (blocking, short timeouts); null if it isn't a Samsung TV. */
    fun query(ip: String, timeoutMs: Int = 1_500): Info? = runCatching {
        val c = URL("http://$ip:$INFO_PORT/api/v2/").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try { if (c.responseCode == 200) parse(c.inputStream.use { it.readBytes().decodeToString() }) else null } finally { c.disconnect() }
    }.getOrNull()

    private fun field(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
}
