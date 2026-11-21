package com.theatresync.core

/**
 * When to mute and unmute the TV for an advert that can't be skipped. Unmuting must never be
 * forgotten, so it happens on whichever comes first:
 *  - the advert's own end time (from its length), [earlySec] early so the film's sound is there at once;
 *  - YouTube saying the advert ended, or the film playing again;
 *  - a safety limit ([maxUnknownMs]) when the advert's length isn't known.
 */
class AdMute(private val earlySec: Double = 0.8, private val maxUnknownMs: Long = 75_000, private val graceMs: Long = 4_000) {
    var muted = false
        private set
    private var mutedAtMs = 0L
    private var endsAtMs = Long.MAX_VALUE

    /** An advert is playing. True if the TV should be muted now (it wasn't yet). */
    fun adPlaying(nowMs: Long, durationSec: Double?, positionSec: Double?): Boolean {
        val wasMuted = muted
        if (!wasMuted) mutedAtMs = nowMs
        muted = true
        endsAtMs = if (durationSec != null && durationSec > 0) {
            nowMs + ((durationSec - (positionSec ?: 0.0) - earlySec) * 1000).toLong().coerceAtLeast(0)
        } else Long.MAX_VALUE
        return !wasMuted
    }

    /** The advert ended or was skipped, or the film is playing. True if the TV should be unmuted now. */
    fun adOver(): Boolean {
        val was = muted
        muted = false; endsAtMs = Long.MAX_VALUE
        return was
    }

    /** Call regularly. True if it's time to unmute (end time reached, or the safety limit passed). */
    fun tick(nowMs: Long): Boolean {
        if (!muted) return false
        val due = if (endsAtMs != Long.MAX_VALUE) nowMs >= endsAtMs else nowMs - mutedAtMs >= maxUnknownMs
        return due && adOver()
    }

    /** Lost contact with YouTube: unmute rather than risk leaving the TV silent (after a short grace). */
    fun connectionLost(nowMs: Long): Boolean = muted && nowMs - mutedAtMs >= graceMs && adOver()
}
