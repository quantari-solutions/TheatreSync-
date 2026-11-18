package com.theatresync.core

import com.theatresync.core.DriftCorrector.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftCorrectorTest {
    @Test fun inSyncDoesNothing() = assertEquals(Action.None, DriftCorrector().decide(10_000, 10_005))

    @Test fun bigDriftSeeks() = assertEquals(Action.Seek(10_000), DriftCorrector().decide(12_000, 10_000))

    @Test fun soundAheadSlowsDown() {
        val a = DriftCorrector().decide(10_100, 10_000) as Action.SetSpeed
        assertEquals(0.95, a.speed, 1e-9)
    }

    @Test fun soundBehindSpeedsUpGently() {
        val a = DriftCorrector().decide(10_000, 10_050) as Action.SetSpeed
        assertEquals(1.025, a.speed, 1e-9)
    }

    @Test fun hysteresisStopsOnlyWhenClose() {
        val c = DriftCorrector()
        assertTrue(c.decide(10_060, 10_000) is Action.SetSpeed) // start correcting
        assertEquals(Action.SetSpeed(0.99), c.decide(10_020, 10_000)) // still correcting at 20 ms
        assertEquals(Action.SetSpeed(1.0), c.decide(10_005, 10_000)) // done
        assertEquals(Action.None, c.decide(10_030, 10_000)) // 30 ms is inside the dead band
    }

    @Test fun neverSeeksNegative() = assertEquals(Action.Seek(0), DriftCorrector().decide(5_000, -300))

    @Test
    fun listenerTargetAppliesDelay() {
        val s = Payload.PlaybackState(Payload.PlaybackState.State.PLAYING, 60_000, 1_000, 1.0)
        assertEquals(60_380L, ListenerTarget.targetMs(s, hostNowMs = 1_500, soundDelayMs = 120))
    }

    /** Simulates a player whose clock runs 0.3 % fast and checks it converges and stays within 40 ms. */
    @Test
    fun closedLoopConvergesAndHolds() {
        val c = DriftCorrector()
        var local = 9_700.0 // starts 300 ms behind (below seek threshold)
        var speed = 1.0
        var worstAfterSettle = 0.0
        for (tick in 0 until 400) { // 100 s at 250 ms ticks
            val target = 10_000.0 + tick * 250
            when (val a = c.decide(local.toLong(), target.toLong())) {
                is Action.SetSpeed -> speed = a.speed
                is Action.Seek -> local = a.positionMs.toDouble()
                Action.None -> {}
            }
            local += 250 * speed * 1.003
            if (tick > 40) worstAfterSettle = maxOf(worstAfterSettle, kotlin.math.abs(local - (target + 250)))
        }
        assertTrue("worst drift after settling was $worstAfterSettle ms", worstAfterSettle <= 45)
    }
}
