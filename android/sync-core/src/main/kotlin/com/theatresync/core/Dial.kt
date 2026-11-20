package com.theatresync.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * DIAL (DIscovery And Launch): how smart TVs that aren't Chromecasts (Samsung "The Frame",
 * LG, Sony, Fire TV…) let phones start YouTube on them. The YouTube app on such a TV
 * reports a "screen ID", after which the same Lounge remote control as on a Chromecast works.
 *
 * Spec: dial-multiscreen.org (DIAL 2.x). Parsing is pure and unit-tested; the network calls
 * are small blocking functions (call off the main thread).
 */
object Dial {
    const val SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1"
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900

    data class Device(
        /** Stable ID: the device UDN (uuid:…) or, failing that, its description URL. */
        val id: String,
        val name: String,
        /** Base URL for apps, e.g. http://192.168.1.40:8080/ws/apps/ */
        val appsUrl: String,
        val manufacturer: String?,
        val model: String?,
    ) {
        /** Chromecasts also answer DIAL; those are handled by the Cast SDK instead. */
        val isChromecast: Boolean
            get() = (manufacturer ?: "").contains("Google", ignoreCase = true) ||
                (model ?: "").let { it.contains("Chromecast", true) || it.contains("Eureka", true) }
    }

    data class AppState(val running: Boolean, val screenId: String?)

    // ------------------------------------------------------------------ parsing (pure)

    fun searchRequest(): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SEARCH_TARGET\r\n\r\n"

    /** The LOCATION header of an SSDP search response, or null if it isn't a DIAL answer. */
    fun locationFrom(response: String): String? {
        val lines = response.split("\r\n", "\n")
        if (lines.none { it.trim().equals("ST: $SEARCH_TARGET", true) || it.contains(SEARCH_TARGET, true) }) return null
        return lines.firstOrNull { it.startsWith("LOCATION:", true) }?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }
    }

    fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?.let(::unescape)?.takeIf { it.isNotBlank() }

    /** Device from its UPnP description XML plus the Application-URL response header. */
    fun deviceFrom(location: String, descriptionXml: String, applicationUrl: String?): Device? {
        val apps = applicationUrl?.trim()?.takeIf { it.startsWith("http") } ?: return null
        val name = tag(descriptionXml, "friendlyName") ?: return null
        return Device(
            id = tag(descriptionXml, "UDN") ?: location,
            name = name,
            appsUrl = if (apps.endsWith("/")) apps else "$apps/",
            manufacturer = tag(descriptionXml, "manufacturer"),
            model = tag(descriptionXml, "modelName"),
        )
    }

    /** GET {apps}/YouTube answer: running/stopped and, when running, the YouTube screen ID. */
    fun appStateFrom(xml: String): AppState = AppState(
        running = tag(xml, "state")?.equals("running", true) == true,
        screenId = tag(xml, "screenId"),
    )

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    // ------------------------------------------------------------------ network (blocking)

    /**
     * Sends an SSDP search and collects DIAL TVs that answer within [timeoutMs].
     * Chromecasts are left out (they are found through the Cast SDK).
     */
    fun discover(timeoutMs: Int = 2_500, http: (String) -> Pair<String, String?> = ::fetchDescription): List<Device> {
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val request = searchRequest().toByteArray()
            val group = InetAddress.getByName(SSDP_HOST)
            repeat(2) { socket.send(DatagramPacket(request, request.size, group, SSDP_PORT)) } // UDP may drop one
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                locationFrom(String(packet.data, 0, packet.length))?.let { locations += it }
            }
        }
        return locations.mapNotNull { loc ->
            runCatching {
                val (xml, appsUrl) = http(loc)
                deviceFrom(loc, xml, appsUrl)
            }.getOrNull()
        }.filterNot { it.isChromecast }.distinctBy { it.id }
    }

    /** Description XML and the Application-URL header. */
    fun fetchDescription(location: String): Pair<String, String?> {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 3_000
        c.readTimeout = 3_000
        try {
            val body = c.inputStream.use { it.readBytes().decodeToString() }
            return body to c.getHeaderField("Application-URL")
        } finally {
            c.disconnect()
        }
    }

    fun youTubeState(device: Device): AppState {
        val c = URL(device.appsUrl + "YouTube").openConnection() as HttpURLConnection
        c.connectTimeout = 3_000
        c.readTimeout = 4_000
        try {
            if (c.responseCode == 404) throw IOException("this TV has no YouTube app for DIAL")
            return appStateFrom(c.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            c.disconnect()
        }
    }

    /** Starts YouTube on the TV (if needed) and waits for its screen ID. */
    fun youTubeScreenId(device: Device, timeoutMs: Long = 20_000): String {
        youTubeState(device).screenId?.let { return it }
        val c = URL(device.appsUrl + "YouTube").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 3_000
        c.readTimeout = 8_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(ByteArray(0)) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw IOException("TV refused to start YouTube ($code)")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1_000)
            runCatching { youTubeState(device) }.getOrNull()?.screenId?.let { return it }
        }
        throw IOException("YouTube on the TV didn't report a screen ID")
    }
}
