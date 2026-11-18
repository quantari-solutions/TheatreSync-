package com.theatresync.core

/**
 * Works out how much extra delay each output needs so that sound and picture
 * reach the viewer at the same moment.
 *
 * Example: Chromecast picture lags ~150 ms, Bluetooth headphones lag ~220 ms.
 * Audio is the slowest path, so the *video* would need delaying by 70 ms — but
 * we usually cannot delay a Cast receiver, so an [Output] can be marked
 * `delayable = false` and the plan reports the remaining unfixable error.
 */
object LatencyAligner {
    data class Output(val id: String, val latencyMs: Int, val userOffsetMs: Int = 0, val delayable: Boolean = true) {
        val effectiveMs: Int get() = latencyMs + userOffsetMs
    }

    data class Plan(
        /** Extra delay to apply per output id. Always >= 0. */
        val delaysMs: Map<String, Int>,
        /** Outputs that could not be aligned because they would need negative delay, with the residual error. */
        val residualMs: Map<String, Int>,
    )

    fun plan(outputs: List<Output>): Plan {
        require(outputs.isNotEmpty()) { "need at least one output" }
        require(outputs.map { it.id }.toSet().size == outputs.size) { "duplicate output id" }
        // Everyone must line up with the slowest output that we cannot speed up.
        val target = outputs.maxOf { it.effectiveMs }
        val delays = mutableMapOf<String, Int>()
        val residual = mutableMapOf<String, Int>()
        for (o in outputs) {
            val needed = target - o.effectiveMs
            if (needed == 0) delays[o.id] = 0
            else if (o.delayable) delays[o.id] = needed
            else { delays[o.id] = 0; residual[o.id] = needed }
        }
        return Plan(delays, residual)
    }
}
