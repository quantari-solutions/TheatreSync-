package com.theatresync.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Type4TagTest {
    private val ok = byteArrayOf(0x90.toByte(), 0x00)
    private fun hex(s: String) = Type4Tag.hex(s.replace(" ", ""))
    private fun sw(r: ByteArray) = r.copyOfRange(r.size - 2, r.size)
    private fun data(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    private val url = "http://192.168.1.82:43245/listen"

    /** The exact sequence an iPhone / Android phone sends to read an NDEF Type 4 tag. */
    @Test fun phoneReadsTheListeningAddress() {
        val s = Type4Tag.Session()
        assertArrayEquals(ok, Type4Tag.respond(s, hex("00 A4 04 00 07 D2760000850101 00"), url))
        assertArrayEquals(ok, Type4Tag.respond(s, hex("00 A4 00 0C 02 E103"), url))
        val cc = Type4Tag.respond(s, hex("00 B0 00 00 0F"), url)
        assertArrayEquals(ok, sw(cc))
        assertEquals(15, data(cc).size)
        assertArrayEquals(ok, Type4Tag.respond(s, hex("00 A4 00 0C 02 E104"), url))
        val nlen = data(Type4Tag.respond(s, hex("00 B0 00 00 02"), url))
        val length = ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF)
        val msg = data(Type4Tag.respond(s, byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x02, length.toByte()), url))
        // Record: D1 01 len 'U' 0x03 "192.168.1.82:43245/listen"
        assertEquals(0xD1.toByte(), msg[0])
        assertEquals('U'.code.toByte(), msg[3])
        assertEquals(0x03.toByte(), msg[4]) // http://
        assertEquals("192.168.1.82:43245/listen", String(msg, 5, msg.size - 5))
    }

    @Test fun nothingToShareWithoutAMovie() {
        val s = Type4Tag.Session()
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), Type4Tag.respond(s, hex("00 A4 04 00 07 D2760000850101 00"), null))
    }

    @Test fun filesNeedTheApplicationFirst() {
        val s = Type4Tag.Session()
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), Type4Tag.respond(s, hex("00 A4 00 0C 02 E104"), url))
    }

    @Test fun readsInChunks() {
        val s = Type4Tag.Session()
        Type4Tag.respond(s, hex("00 A4 04 00 07 D2760000850101 00"), url)
        Type4Tag.respond(s, hex("00 A4 00 0C 02 E104"), url)
        val whole = Type4Tag.ndefForUrl(url)
        val a = data(Type4Tag.respond(s, hex("00 B0 00 00 10"), url))
        val b = data(Type4Tag.respond(s, byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x10, 0x3B), url))
        assertArrayEquals(whole, a + b)
    }
}
