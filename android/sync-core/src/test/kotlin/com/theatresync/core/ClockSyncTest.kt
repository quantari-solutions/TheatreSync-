package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ClockSyncTest {
    /** Simulates an exchange where host clock = listener clock + [trueOffset]. */
    private fun exchange(cs: ClockSync, t0: Long, trueOffset: Long, upMs: Long, downMs: Long, hostProcMs: Long = 1) {
        val t1 = t0 + upMs + trueOffset
        val t2 = t1 + hostProcMs
        val t3 = t2 - trueOffset + downMs
        cs.addExchange(Payload.ClockPong(0, t0, t1, t2), t3)
    }

    @Test fun emptyHasNoOffset() = assertNull(ClockSync().offsetMs())

    @Test
    fun symmetricPathRecoversOffsetExactly() {
        val cs = ClockSync()
        exchange(cs, t0 = 1_000, trueOffset = 5_000, upMs = 10, downMs = 10)
        assertEquals(5_000.0, cs.offsetMs()!!, 0.001)
        assertEquals(3_990L, cs.hostToLocal(8_990))
    }

    @Test
    fun jitteryAsymmetricSamplesAreRejectedInFavourOfFastOnes() {
        val cs = ClockSync()
        // 3 clean, fast samples and 9 slow, asymmetric (Wi-Fi retry) ones.
        repeat(3) { exchange(cs, t0 = 1_000L + it * 100, trueOffset = 250, upMs = 4, downMs = 4) }
        repeat(9) { exchange(cs, t0 = 5_000L + it * 100, trueOffset = 250, upMs = 120, downMs = 8) }
        assertEquals(250.0, cs.offsetMs()!!, 0.5)
        assertEquals(8L, cs.bestRttMs())
    }

    @Test
    fun inconsistentTimestampsIgnored() {
        val cs = ClockSync()
        assertFalse(cs.addExchange(Payload.ClockPong(0, t0 = 100, t1 = 50, t2 = 40), t3 = 120))
        assertEquals(0, cs.sampleCount)
    }

    @Test
    fun keepsOnlyMaxSamples() {
        val cs = ClockSync(maxSamples = 4)
        repeat(10) { exchange(cs, t0 = it * 10L, trueOffset = 0, upMs = 1, downMs = 1) }
        assertEquals(4, cs.sampleCount)
    }
}
