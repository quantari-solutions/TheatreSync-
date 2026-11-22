package com.theatresync.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class NetworkPowerTest {
    /** A fake device: [greet] on connect, then answers each received chunk with [reply]. Records what it got. */
    private fun fake(greet: String? = null, reply: (String) -> String?): Pair<Int, MutableList<String>> {
        val got = CopyOnWriteArrayList<String>()
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val out = it.getOutputStream()
                        greet?.let { g -> out.write(g.toByteArray()); out.flush() }
                        val buf = ByteArray(512)
                        while (true) {
                            val n = runCatching { it.getInputStream().read(buf) }.getOrDefault(-1)
                            if (n <= 0) break
                            val msg = String(buf, 0, n, Charsets.ISO_8859_1); got += msg
                            reply(msg)?.let { r -> out.write(r.toByteArray(Charsets.ISO_8859_1)); out.flush() }
                        }
                    }
                }
            }
        }
        return ss.localPort to got
    }

    @Test fun pjlinkProjectorFoundAndSwitchedOff() {
        val (port, got) = fake("PJLINK 0\r") { if (it.contains("POWR")) "%1POWR=OK\r" else null }
        val d = NetworkPower.probe("127.0.0.1", NetworkPower.Kind.PJLINK, port)!!
        assertTrue(NetworkPower.setPower(d, on = false))
        assertTrue(got.any { it == "%1POWR 0\r" })
    }

    @Test fun pjlinkWithPasswordSendsTheDigestFirst() {
        val (port, got) = fake("PJLINK 1 498e4a67\r") { if (it.contains("POWR")) "%1POWR=OK\r" else null }
        assertTrue(NetworkPower.setPower(NetworkPower.Device(NetworkPower.Kind.PJLINK, "127.0.0.1", port), on = true))
        assertTrue(got.any { it == NetworkPower.pjlinkAuthPrefix("498e4a67") + "%1POWR 1\r" })
    }

    @Test fun denonReceiverFoundAndSwitchedToStandby() {
        val (port, got) = fake { if (it.startsWith("PW?")) "PWON\r" else null }
        val d = NetworkPower.probe("127.0.0.1", NetworkPower.Kind.DENON, port)!!
        assertTrue(NetworkPower.setPower(d, on = false))
        Thread.sleep(200)
        assertTrue(got.any { it == "PWSTANDBY\r" })
    }

    @Test fun onkyoPacketIsCorrect() {
        val p = NetworkPower.onkyoPower(on = false)
        assertEquals("ISCP", String(p, 0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 16), p.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0, 0, 0, 8), p.copyOfRange(8, 12)) // "!1PWR00\r"
        assertEquals("!1PWR00\r", String(p, 16, p.size - 16))
    }

    @Test fun onkyoReceiverFound() {
        val (port, _) = fake { if (it.contains("PWRQSTN")) String(NetworkPower.eiscp("PWR01"), Charsets.ISO_8859_1) else null }
        assertTrue(NetworkPower.probe("127.0.0.1", NetworkPower.Kind.ONKYO, port) != null)
    }

    @Test fun somethingElseOnTheSamePortIsNotMistakenForADevice() {
        val (port, _) = fake("SSH-2.0-OpenSSH\r") { "nope\r" }
        assertNull(NetworkPower.probe("127.0.0.1", NetworkPower.Kind.PJLINK, port))
        assertNull(NetworkPower.probe("127.0.0.1", NetworkPower.Kind.DENON, port))
    }

    @Test fun savedDevicesRoundTrip() {
        val d = NetworkPower.Device(NetworkPower.Kind.YAMAHA, "192.168.1.40")
        assertEquals(d, NetworkPower.Device.decode(d.encode()))
        assertEquals("/YamahaExtendedControl/v1/main/setPower?power=standby", NetworkPower.yamahaPowerPath(false))
    }
}
