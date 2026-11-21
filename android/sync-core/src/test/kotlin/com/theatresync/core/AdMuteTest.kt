package com.theatresync.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMuteTest {
    @Test fun unmutesJustBeforeTheAdvertEndsByItself() {
        val m = AdMute()
        assertTrue(m.adPlaying(0, durationSec = 15.0, positionSec = 0.0))
        assertFalse(m.tick(14_000))
        assertTrue("0.8 s before the end", m.tick(14_250))
        assertFalse(m.muted)
    }

    @Test fun unmutesWhenYouTubeSaysItsOver() {
        val m = AdMute()
        m.adPlaying(0, null, null)
        assertTrue(m.adOver())
        assertFalse("only once", m.adOver())
    }

    @Test fun neverStaysMutedWhenTheLengthIsUnknown() {
        val m = AdMute(maxUnknownMs = 75_000)
        m.adPlaying(0, null, null)
        assertFalse(m.tick(60_000))
        assertTrue(m.tick(75_000))
    }

    @Test fun nextAdvertInABreakKeepsItMutedAndMovesTheEnd() {
        val m = AdMute()
        assertTrue(m.adPlaying(0, 6.0, 0.0))
        assertFalse("already muted", m.adPlaying(5_000, 20.0, 0.0))
        assertFalse(m.tick(6_000))
        assertTrue(m.tick(24_300))
    }

    @Test fun lostContactUnmutesAfterAShortGrace() {
        val m = AdMute(graceMs = 4_000)
        m.adPlaying(0, 30.0, 0.0)
        assertFalse(m.connectionLost(2_000))
        assertTrue(m.connectionLost(4_000))
    }
}
