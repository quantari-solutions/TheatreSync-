package com.theatresync.core

/** Predicts the media position at any host time from the last [Payload.PlaybackState]. */
object PlaybackClock {
    fun positionAt(state: Payload.PlaybackState, hostNowMs: Long): Long = when (state.state) {
        Payload.PlaybackState.State.PLAYING -> {
            val elapsed = (hostNowMs - state.hostTimeMs).coerceAtLeast(0)
            state.mediaPositionMs + Math.round(elapsed * state.playbackRate)
        }
        else -> state.mediaPositionMs
    }

    /**
     * Drift between where a local output actually is and where it should be.
     * Positive = local output is AHEAD of the host and must slow down / wait.
     */
    fun driftMs(state: Payload.PlaybackState, hostNowMs: Long, localPositionMs: Long): Long =
        localPositionMs - positionAt(state, hostNowMs)
}
