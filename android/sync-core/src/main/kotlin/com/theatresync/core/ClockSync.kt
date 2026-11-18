package com.theatresync.core

/**
 * NTP-style clock offset estimation between a listener and the host.
 *
 * For a ping sent at listener time t0, received by the host at t1, answered at t2
 * and received back by the listener at t3:
 *   offset = ((t1 - t0) + (t2 - t3)) / 2      (host clock minus listener clock)
 *   rtt    = (t3 - t0) - (t2 - t1)
 *
 * Samples with the lowest round-trip time are the least affected by Wi-Fi jitter,
 * so the estimate is the median offset of the fastest [bestFraction] of samples.
 */
class ClockSync(
    private val maxSamples: Int = 32,
    private val bestFraction: Double = 0.25,
) {
    data class Sample(val offsetMs: Double, val rttMs: Long)

    private val samples = ArrayDeque<Sample>()

    /** Adds a completed exchange. Returns false (and ignores it) if the timestamps are inconsistent. */
    fun addExchange(pong: Payload.ClockPong, t3: Long): Boolean {
        val rtt = (t3 - pong.t0) - (pong.t2 - pong.t1)
        if (rtt < 0 || pong.t2 < pong.t1 || t3 < pong.t0) return false
        val offset = ((pong.t1 - pong.t0) + (pong.t2 - t3)) / 2.0
        samples.addLast(Sample(offset, rtt))
        while (samples.size > maxSamples) samples.removeFirst()
        return true
    }

    val sampleCount: Int get() = samples.size

    /** Estimated host-minus-listener offset in ms, or null until at least one sample exists. */
    fun offsetMs(): Double? {
        if (samples.isEmpty()) return null
        val n = maxOf(1, (samples.size * bestFraction).toInt())
        val best = samples.sortedBy { it.rttMs }.take(n).map { it.offsetMs }.sorted()
        return if (best.size % 2 == 1) best[best.size / 2]
        else (best[best.size / 2 - 1] + best[best.size / 2]) / 2.0
    }

    /** Best (lowest) round-trip time observed, a rough quality indicator for the UI. */
    fun bestRttMs(): Long? = samples.minOfOrNull { it.rttMs }

    fun hostToLocal(hostMs: Long): Long? = offsetMs()?.let { Math.round(hostMs - it) }
    fun localToHost(localMs: Long): Long? = offsetMs()?.let { Math.round(localMs + it) }

    fun reset() = samples.clear()
}
