package com.theatresync.core

/**
 * "Play along": Netflix / Prime Video play on the TV, and the same title plays in the Netflix / Prime app
 * on this phone with its sound in the headphones. These apps don't let anyone take the TV's sound, so the
 * phone plays its own copy and this keeps it in step with the TV: it follows play / pause, and jumps when
 * the phone drifts. Those apps only allow coarse jumps (a seek lands a little late while they buffer), so
 * after each jump the remaining error is learned and the next jump aims that much further ahead.
 */
class PlayAlongSync(
    private val toleranceMs: Long = 600,
    private val minGapMs: Long = 6_000,
    private val bigJumpMs: Long = 5_000,
) {
    sealed class Action {
        object None : Action()
        object Play : Action()
        object Pause : Action()
        data class SeekTo(val positionMs: Long) : Action()
    }

    /** How far ahead a jump aims, learned from where the last jumps landed. */
    var leadMs: Long = 400
        private set
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var pendingTarget: Long? = null
    private var pendingAt = 0L

    /** Phone ahead (+) or behind (−) the TV, in ms, at the last check; null when not comparable. */
    var driftMs: Long? = null
        private set

    /**
     * [phonePosMs]/[phonePlaying]: the phone app now. [tvPosMs]/[tvPlaying]: the TV now (already moved on
     * to this moment). [nowMs]: a steady clock.
     */
    fun decide(phonePosMs: Long, phonePlaying: Boolean, tvPosMs: Long, tvPlaying: Boolean, nowMs: Long): Action {
        if (!tvPlaying) { driftMs = null; pendingTarget = null; return if (phonePlaying) Action.Pause else Action.None }
        if (!phonePlaying) { driftMs = null; return Action.Play }
        val drift = phonePosMs - tvPosMs
        driftMs = drift
        // Learn from the last jump, once it has settled (~2.5 s): aim further ahead by what it fell short.
        pendingTarget?.let {
            if (nowMs - pendingAt > 2_500) {
                leadMs = (leadMs - drift * 3 / 4).coerceIn(0, 4_000)
                pendingTarget = null
            } else return Action.None
        }
        val far = kotlin.math.abs(drift)
        if (far > toleranceMs && (nowMs - lastSeekAt > minGapMs || far > bigJumpMs)) {
            val target = (tvPosMs + leadMs).coerceAtLeast(0)
            lastSeekAt = nowMs; pendingTarget = target; pendingAt = nowMs
            return Action.SeekTo(target)
        }
        return Action.None
    }
}
