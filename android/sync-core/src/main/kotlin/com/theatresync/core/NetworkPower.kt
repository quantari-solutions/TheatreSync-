package com.theatresync.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest

/**
 * Turning the projector and AV receiver on and off over the Wi-Fi, without knowing their model: most
 * network-connected ones speak one of these standard control languages.
 *
 *  - PJLink (port 4352): the projector industry's standard (Epson, BenQ, Sony, NEC, Optoma, ViewSonic…)
 *  - Denon / Marantz (port 23): "PWON" / "PWSTANDBY"
 *  - Onkyo / Pioneer / Integra (eISCP, port 60128): "!1PWR01" / "!1PWR00"
 *  - Yamaha MusicCast (HTTP): /YamahaExtendedControl/v1/main/setPower
 *
 * [probe] checks a device really speaks the language (so a router's open port isn't mistaken for one);
 * [setPower] sends on/standby. The Chromecast itself can only wake things (HDMI-CEC), never switch off.
 */
object NetworkPower {
    enum class Kind(val port: Int, val label: String) {
        PJLINK(4352, "Projector (PJLink)"),
        DENON(23, "Denon / Marantz receiver"),
        ONKYO(60128, "Onkyo / Pioneer / Integra receiver"),
        YAMAHA(80, "Yamaha receiver"),
    }

    data class Device(val kind: Kind, val host: String, val port: Int = kind.port) {
        /** Saved as "KIND|host|port". */
        fun encode() = "${kind.name}|$host|$port"
        companion object {
            fun decode(s: String): Device? = s.split('|').takeIf { it.size == 3 }?.let { (k, h, p) ->
                runCatching { Device(Kind.valueOf(k), h, p.toInt()) }.getOrNull()
            }
        }
    }

    // ------------------------------------------------------------------ the languages

    fun pjlinkPower(on: Boolean) = "%1POWR ${if (on) 1 else 0}\r"

    /** PJLink with a password: MD5(random + password) in hex goes in front of the command. */
    fun pjlinkAuthPrefix(random: String, password: String = ""): String =
        MessageDigest.getInstance("MD5").digest((random + password).toByteArray()).joinToString("") { "%02x".format(it) }

    fun denonPower(on: Boolean) = if (on) "PWON\r" else "PWSTANDBY\r"

    /** An eISCP packet: "ISCP", header size 16, data size, version 1, then "!1<command>\r". */
    fun eiscp(command: String): ByteArray {
        val data = "!1$command\r".toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ISCP".toByteArray())
        out.write(int32(16)); out.write(int32(data.size))
        out.write(byteArrayOf(1, 0, 0, 0))
        out.write(data)
        return out.toByteArray()
    }

    fun onkyoPower(on: Boolean) = eiscp(if (on) "PWR01" else "PWR00")

    fun yamahaPowerPath(on: Boolean) = "/YamahaExtendedControl/v1/main/setPower?power=${if (on) "on" else "standby"}"

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    // ------------------------------------------------------------------ talking to them

    /** True if something is listening on [host]:[port] (a quick first pass before [probe]). */
    fun portOpen(host: String, port: Int, timeoutMs: Int = 300): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } }.getOrDefault(false)

    /** The device at [host] if it really speaks [kind]'s language, else null. */
    fun probe(host: String, kind: Kind, port: Int = kind.port, timeoutMs: Int = 1_500): Device? = runCatching {
        val d = Device(kind, host, port)
        when (kind) {
            Kind.PJLINK -> socket(host, port, timeoutMs).use { s -> readLine(s.getInputStream())?.takeIf { it.startsWith("PJLINK ") }?.let { d } }
            Kind.DENON -> socket(host, port, timeoutMs).use { s ->
                s.getOutputStream().apply { write("PW?\r".toByteArray()); flush() }
                readLine(s.getInputStream())?.takeIf { it.startsWith("PWON") || it.startsWith("PWSTANDBY") }?.let { d }
            }
            Kind.ONKYO -> socket(host, port, timeoutMs).use { s ->
                s.getOutputStream().apply { write(eiscp("PWRQSTN")); flush() }
                val got = readSome(s.getInputStream(), 64)
                if (got.startsWith("ISCP") && got.contains("!1PWR")) d else null
            }
            Kind.YAMAHA -> http("http://$host:$port/YamahaExtendedControl/v1/system/getDeviceInfo", timeoutMs)
                ?.takeIf { it.contains("\"model_name\"") && it.contains("\"response_code\":0") }?.let { d }
        }
    }.getOrNull()

    /** Switches [d] on or to standby. True if it accepted. */
    fun setPower(d: Device, on: Boolean, timeoutMs: Int = 4_000): Boolean = runCatching {
        when (d.kind) {
            Kind.PJLINK -> socket(d.host, d.port, timeoutMs).use { s ->
                val greeting = readLine(s.getInputStream()) ?: return false
                val prefix = if (greeting.startsWith("PJLINK 1 ")) pjlinkAuthPrefix(greeting.removePrefix("PJLINK 1 ").trim()) else ""
                s.getOutputStream().apply { write((prefix + pjlinkPower(on)).toByteArray()); flush() }
                readLine(s.getInputStream())?.let { it.startsWith("%1POWR=OK") || it.startsWith("%1POWR=ERR3") } ?: false // ERR3: busy warming/cooling
            }
            Kind.DENON -> socket(d.host, d.port, timeoutMs).use { s ->
                s.getOutputStream().apply { write(denonPower(on).toByteArray()); flush() }; true
            }
            Kind.ONKYO -> socket(d.host, d.port, timeoutMs).use { s ->
                s.getOutputStream().apply { write(onkyoPower(on)); flush() }; true
            }
            Kind.YAMAHA -> http("http://${d.host}:${d.port}${yamahaPowerPath(on)}", timeoutMs)?.contains("\"response_code\":0") == true
        }
    }.getOrDefault(false)

    private fun socket(host: String, port: Int, timeoutMs: Int) = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = timeoutMs }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 256) {
            val c = input.read()
            if (c < 0) break
            if (c == '\r'.code || c == '\n'.code) { if (sb.isNotEmpty()) break else continue }
            sb.append(c.toChar())
        }
        return sb.toString().ifEmpty { null }
    }

    private fun readSome(input: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) { val r = input.read(buf, n, max - n); if (r <= 0) break; n += r; if (String(buf, 0, n, Charsets.ISO_8859_1).contains("\u001a") || n >= 24) break }
        return String(buf, 0, n, Charsets.ISO_8859_1)
    }

    private fun http(url: String, timeoutMs: Int): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else null } finally { c.disconnect() }
    }
}
