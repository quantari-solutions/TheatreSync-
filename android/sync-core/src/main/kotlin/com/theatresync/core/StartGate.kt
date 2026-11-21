package com.theatresync.core

/**
 * Smart start: the TV loads the movie paused, and this decides when it has enough of it in hand to
 * play without stopping to buffer (what the owner did by hand: pause a while, then play).
 *
 * "In hand" = movie bytes the TV has fetched from the phone since loading, turned into seconds of
 * film with the file's average bitrate. Ready when the TV holds [targetSec] of film; or sooner, from
 * [minSec], when the Wi-Fi is clearly faster than the film needs ([fastFactor]); or after [maxWaitMs]
 * whatever happens, so a movie never waits forever.
 */
class StartGate(
    /** Average bits per second of the file the TV plays (size × 8 ÷ duration). */
    private val bitrateBps: Double,
    private val targetSec: Double = 20.0,
    private val minSec: Double = 6.0,
    private val fastFactor: Double = 1.5,
    private val maxWaitMs: Long = 45_000,
) {
    data class Status(val ready: Boolean, val percent: Int, val etaSec: Long?, val bufferedSec: Double, val speedBps: Double?)

    private var startMs = -1L
    private var startBytes = 0L
    private val samples = ArrayDeque<Pair<Long, Long>>() // (ms, bytes)

    fun update(nowMs: Long, tvBytes: Long): Status {
        if (startMs < 0) { startMs = nowMs; startBytes = tvBytes }
        samples.addLast(nowMs to tvBytes)
        while (samples.size > 2 && nowMs - samples.first().first > 4_000) samples.removeFirst()
        val got = (tvBytes - startBytes).coerceAtLeast(0)
        val bufferedSec = if (bitrateBps > 0) got * 8 / bitrateBps else 0.0
        val (t0, b0) = samples.first()
        val speed = if (nowMs - t0 >= 1_000) (tvBytes - b0) * 8.0 * 1000 / (nowMs - t0) else null
        val fast = speed != null && bitrateBps > 0 && speed >= fastFactor * bitrateBps
        val waited = nowMs - startMs
        val ready = bitrateBps <= 0 || bufferedSec >= targetSec || (fast && bufferedSec >= minSec) || waited >= maxWaitMs
        val goal = if (fast) minSec else targetSec
        val percent = if (ready) 100 else ((bufferedSec / goal) * 100).toInt().coerceIn(0, 99)
        val eta = when {
            ready -> 0L
            speed != null && speed > 0 -> minOf(((goal - bufferedSec) * bitrateBps / speed).toLong() + 1, (maxWaitMs - waited) / 1000)
            else -> null
        }
        return Status(ready, percent, eta, bufferedSec, speed)
    }
}
