package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayAlongSyncTest {
    @Test fun followsPauseAndPlay() {
        val s = PlayAlongSync()
        assertEquals(PlayAlongSync.Action.Pause, s.decide(10_000, true, 10_000, false, 0))
        assertEquals(PlayAlongSync.Action.Play, s.decide(10_000, false, 10_000, true, 100))
        assertEquals(PlayAlongSync.Action.None, s.decide(10_000, false, 10_000, false, 200))
    }

    @Test fun inStepNeedsNothing() {
        val s = PlayAlongSync()
        assertEquals(PlayAlongSync.Action.None, s.decide(60_200, true, 60_000, true, 1_000))
        assertEquals(200L, s.driftMs)
    }

    @Test fun jumpsAheadByTheLeadAndLearnsIt() {
        val s = PlayAlongSync()
        val a = s.decide(50_000, true, 60_000, true, 10_000) // 10 s behind
        assertTrue(a is PlayAlongSync.Action.SeekTo)
        assertEquals(60_400L, (a as PlayAlongSync.Action.SeekTo).positionMs)
        // Settled 800 ms behind (the app buffered): the next jumps aim 600 ms further ahead.
        assertEquals(PlayAlongSync.Action.None, s.decide(70_000, true, 70_800, true, 13_000))
        assertEquals(1_000L, s.leadMs)
    }

    @Test fun doesNotJumpAgainTooSoon() {
        val s = PlayAlongSync()
        s.decide(50_000, true, 60_000, true, 0)
        s.decide(63_100, true, 63_000, true, 3_000) // settles, learns
        assertEquals(PlayAlongSync.Action.None, s.decide(64_000, true, 64_900, true, 4_000)) // small drift, too soon
    }
}
