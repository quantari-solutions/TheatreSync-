package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class SamsungRemoteTest {
    @Test fun frameRoundTrip() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val f = SamsungRemote.textFrame("hello", mask)
        assertEquals(0x81, f[0].toInt() and 0xFF)
        assertEquals(0x80 or 5, f[1].toInt() and 0xFF)
        val unmasked = ByteArray(5) { (f[6 + it].toInt() xor mask[it % 4].toInt()).toByte() }
        assertEquals("hello", String(unmasked))
    }

    @Test fun magicPacketHasSixteenMacs() {
        val p = SamsungRemote.magicPacket("aa:bb:cc:dd:ee:ff")
        assertEquals(6 + 16 * 6, p.size)
        assertEquals(0xAA, p[6].toInt() and 0xFF)
    }

    /** A fake TV: accepts the WebSocket, says "connected", and records the key sent. */
    @Test fun sendsAKeyToAnAllowedTv() {
        ServerSocket(0).use { srv ->
            var got = ""
            val t = Thread {
                srv.accept().use { c ->
                    val inp = c.getInputStream(); val sb = StringBuilder()
                    while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
                    val msg = """{"event":"ms.channel.connect"}""".toByteArray()
                    out.write(byteArrayOf(0x81.toByte(), msg.size.toByte())); out.write(msg); out.flush()
                    inp.read(); var len = inp.read() and 0x7F; if (len == 126) len = (inp.read() shl 8) or inp.read()
                    val mask = ByteArray(4).also { inp.read(it) }
                    val data = ByteArray(len).also { var n = 0; while (n < len) n += inp.read(it, n, len - n) }
                    got = String(ByteArray(len) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() })
                }
            }.apply { start() }
            // Port 8001 is fixed in the client, so exercise the protocol pieces against the fake instead.
            val s = java.net.Socket("127.0.0.1", srv.localPort)
            s.getOutputStream().write("GET ${SamsungRemote.channelPath()} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val inp = s.getInputStream(); val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
            assertTrue(SamsungRemote.readTextFrame(inp)!!.contains("ms.channel.connect"))
            s.getOutputStream().write(SamsungRemote.textFrame(SamsungRemote.clickJson(SamsungRemote.Keys.VOL_UP)))
            t.join(3_000); s.close()
            assertTrue(got.contains("KEY_VOLUP"))
        }
    }
}
