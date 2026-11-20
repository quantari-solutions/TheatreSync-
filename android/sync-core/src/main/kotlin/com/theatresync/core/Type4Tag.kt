package com.theatresync.core

/**
 * TapIn: the host phone pretends to be an NFC tag (NFC Forum Type 4 Tag, via Android Host Card
 * Emulation) that holds one web address. A guest's iPhone or Android phone reads it with a tap
 * and opens the listening page in its browser: no app needed.
 *
 * Implements just the commands phones use to read an NDEF Type 4 tag: SELECT the NDEF
 * application, SELECT the capability container / NDEF file, READ BINARY. Pure and unit-tested.
 */
object Type4Tag {
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    private val WRONG = byteArrayOf(0x6D, 0x00)
    private val NDEF_AID = hex("D2760000850101")
    private val CC_FILE = hex("E103")
    private val NDEF_FILE = hex("E104")

    /** Capability container: version 2.0, max read 0x3B, NDEF file E104 up to 0x7FFF bytes, read-only. */
    private val CC = hex("000F20003B00340406E1047FFF00FF")

    /** Per-reader state: which file the reader selected. */
    class Session {
        internal var selected: ByteArray? = null
        internal var appSelected = false
    }

    /** NDEF message with a single URI record (http:// prefix compressed, as the spec allows). */
    fun ndefForUrl(url: String): ByteArray {
        val (code, rest) = when {
            url.startsWith("http://www.") -> 0x01 to url.removePrefix("http://www.")
            url.startsWith("https://www.") -> 0x02 to url.removePrefix("https://www.")
            url.startsWith("http://") -> 0x03 to url.removePrefix("http://")
            url.startsWith("https://") -> 0x04 to url.removePrefix("https://")
            else -> 0x00 to url
        }
        val payload = byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8)
        require(payload.size < 256) { "URL too long for a short NDEF record" }
        // MB | ME | SR, TNF = well-known (1); type "U"
        val record = byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 'U'.code.toByte()) + payload
        return byteArrayOf((record.size shr 8).toByte(), record.size.toByte()) + record // NLEN + message
    }

    /** Answers one command APDU. [url] null = nothing to share right now (no movie playing). */
    fun respond(session: Session, apdu: ByteArray, url: String?): ByteArray {
        if (apdu.size < 4) return WRONG
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (ins) {
            0xA4 -> { // SELECT
                val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
                val data = if (apdu.size >= 5 + lc) apdu.copyOfRange(5, 5 + lc) else ByteArray(0)
                when {
                    p1 == 0x04 && data.contentEquals(NDEF_AID) -> {
                        if (url == null) return NOT_FOUND
                        session.appSelected = true; session.selected = null; OK
                    }
                    p1 == 0x00 && session.appSelected && (data.contentEquals(CC_FILE) || data.contentEquals(NDEF_FILE)) -> {
                        session.selected = data; OK
                    }
                    else -> NOT_FOUND
                }
            }
            0xB0 -> { // READ BINARY
                val file = when {
                    session.selected?.contentEquals(CC_FILE) == true -> CC
                    session.selected?.contentEquals(NDEF_FILE) == true -> ndefForUrl(url ?: return NOT_FOUND)
                    else -> return NOT_FOUND
                }
                val offset = (p1 shl 8) or p2
                val le = if (apdu.size > 4) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
                if (offset > file.size) return byteArrayOf(0x6B, 0x00)
                file.copyOfRange(offset, minOf(file.size, offset + le)) + OK
            }
            else -> WRONG
        }
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
