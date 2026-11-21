package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateTest {
    private val film = 8_000_000.0 // 8 Mbit/s movie = 1 MB per second of film

    @Test fun slowWifiWaitsForTwentySecondsOfFilm() {
        val g = StartGate(film)
        var t = 0L; var bytes = 0L
        var s = g.update(t, bytes)
        // Wi-Fi delivers 1.2 MB/s (just above the film's 1 MB/s): not "fast", so wait for 20 s in hand.
        while (!s.ready) { t += 500; bytes += 600_000; s = g.update(t, bytes) }
        assertTrue(s.bufferedSec >= 20.0)
        assertTrue(t in 16_000..18_000)
    }

    @Test fun fastWifiStartsAfterSixSeconds() {
        val g = StartGate(film)
        var t = 0L; var bytes = 0L
        var s = g.update(t, bytes)
        while (!s.ready) { t += 250; bytes += 1_000_000; s = g.update(t, bytes) } // 4 MB/s
        assertTrue(s.bufferedSec in 6.0..9.0)
    }

    @Test fun progressAndTimeLeftAreShown() {
        val g = StartGate(film)
        g.update(0, 0)
        val s = g.update(2_000, 2_000_000) // 1 MB/s: 2 s of film
        assertFalse(s.ready)
        assertEquals(10, s.percent)
        assertEquals(19L, s.etaSec) // 18 s of film left at 1 film-second per second, +1
    }

    @Test fun neverWaitsForever() {
        val g = StartGate(film, maxWaitMs = 45_000)
        g.update(0, 0)
        assertTrue(g.update(45_000, 1_000).ready)
    }

    @Test fun unknownBitrateStartsAtOnce() = assertTrue(StartGate(0.0).update(0, 0).ready)
}
