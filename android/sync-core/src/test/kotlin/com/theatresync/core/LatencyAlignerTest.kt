package com.theatresync.core

import com.theatresync.core.LatencyAligner.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyAlignerTest {
    @Test
    fun fasterOutputsAreDelayedToMatchSlowest() {
        val plan = LatencyAligner.plan(listOf(Output("cast", 150), Output("xm4", 220), Output("speaker", 40)))
        assertEquals(mapOf("cast" to 70, "xm4" to 0, "speaker" to 180), plan.delaysMs)
        assertTrue(plan.residualMs.isEmpty())
    }

    @Test
    fun undelayableOutputReportsResidual() {
        val plan = LatencyAligner.plan(listOf(Output("cast", 150, delayable = false), Output("xm4", 220)))
        assertEquals(0, plan.delaysMs["cast"])
        assertEquals(mapOf("cast" to 70), plan.residualMs)
    }

    @Test
    fun userOffsetShiftsAlignment() {
        val plan = LatencyAligner.plan(listOf(Output("cast", 150), Output("xm4", 220, userOffsetMs = -30)))
        assertEquals(mapOf("cast" to 40, "xm4" to 0), plan.delaysMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsRejected() { LatencyAligner.plan(listOf(Output("a", 1), Output("a", 2))) }
}
