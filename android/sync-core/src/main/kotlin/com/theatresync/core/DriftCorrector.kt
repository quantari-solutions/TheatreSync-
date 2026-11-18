package com.theatresync.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps a headphone player in step with the TV picture.
 *
 * Small drift is removed smoothly by playing a few percent faster/slower (pitch is
 * preserved by the players), so nobody hears a jump. Large drift (start-up, a seek,
 * a stall) is fixed by seeking.
 *
 * The same rules are mirrored in ios/TheatreSync.swiftpm/Shared/Sync.swift.
 */
class DriftCorrector(
    private val startCorrectingMs: Long = 40,
    private val stopCorrectingMs: Long = 10,
    private val seekThresholdMs: Long = 400,
    private val maxSpeedDelta: Double = 0.05,
    /** Time over which a drift is removed: drift 100 ms / 2000 ms → 5 % speed change. */
    private val catchUpMs: Long = 2_000,
) {
    sealed interface Action {
        data object None : Action
        data class SetSpeed(val speed: Double) : Action
        data class Seek(val positionMs: Long) : Action
    }

    private var correcting = false
    private var currentSpeed = 1.0

    /**
     * @param localMs where the headphone player currently is
     * @param targetMs where it should be (picture position minus the user's sound delay)
     */
    fun decide(localMs: Long, targetMs: Long): Action {
        val drift = localMs - targetMs // positive = sound ahead of picture
        val magnitude = abs(drift)
        if (magnitude >= seekThresholdMs) {
            correcting = false
            return Seek(targetMs.coerceAtLeast(0)).also { currentSpeed = 1.0 }
        }
        if (correcting && magnitude <= stopCorrectingMs) correcting = false
        else if (!correcting && magnitude >= startCorrectingMs) correcting = true

        val wanted = if (!correcting) 1.0 else {
            val raw = 1.0 - drift.toDouble() / catchUpMs
            ((raw.coerceIn(1.0 - maxSpeedDelta, 1.0 + maxSpeedDelta)) * 200).roundToInt() / 200.0
        }
        if (wanted == currentSpeed) return Action.None
        currentSpeed = wanted
        return Action.SetSpeed(wanted)
    }

    /**
     * Call when the target jumps by a small amount on purpose (the user tapped
     * "Sound is early/late"), so even a 20 ms change is applied smoothly instead
     * of being ignored as inside the dead band.
     */
    fun forceCorrect() {
        correcting = true
    }

    /** Call after the player was paused or re-created so the next decision starts fresh. */
    fun reset() {
        correcting = false
        currentSpeed = 1.0
    }

    private fun Seek(position: Long) = Action.Seek(position)
}

/** Where a listener's headphones should be right now. */
object ListenerTarget {
    /**
     * @param state latest playback_state from the host
     * @param hostNowMs the current time on the host clock (via [ClockSync.localToHost])
     * @param soundDelayMs user trim; positive = hear the sound later
     */
    fun targetMs(state: Payload.PlaybackState, hostNowMs: Long, soundDelayMs: Int): Long =
        (PlaybackClock.positionAt(state, hostNowMs) - soundDelayMs).coerceAtLeast(0)
}
