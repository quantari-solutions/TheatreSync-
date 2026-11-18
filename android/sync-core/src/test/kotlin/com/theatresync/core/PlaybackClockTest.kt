package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockTest {
    private fun state(s: Payload.PlaybackState.State, rate: Double = 1.0) =
        Payload.PlaybackState(s, mediaPositionMs = 60_000, hostTimeMs = 10_000, playbackRate = rate)

    @Test fun playingAdvances() =
        assertEquals(61_500L, PlaybackClock.positionAt(state(Payload.PlaybackState.State.PLAYING), 11_500))

    @Test fun rateIsApplied() =
        assertEquals(62_000L, PlaybackClock.positionAt(state(Payload.PlaybackState.State.PLAYING, 2.0), 11_000))

    @Test fun pausedHolds() =
        assertEquals(60_000L, PlaybackClock.positionAt(state(Payload.PlaybackState.State.PAUSED), 99_000))

    @Test fun neverRewindsForStaleHostTime() =
        assertEquals(60_000L, PlaybackClock.positionAt(state(Payload.PlaybackState.State.PLAYING), 9_000))

    @Test fun driftSign() =
        assertEquals(40L, PlaybackClock.driftMs(state(Payload.PlaybackState.State.PLAYING), 11_000, 61_040))
}
