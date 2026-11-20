package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestsTest {
    private var now = 1_000L
    private var changes = 0
    private val g = Guests({ now }).also { it.onChange = { changes++ } }

    @Test fun firstReportAddsTheGuestAtOnce() {
        val c = g.report("web-a", "Sam", 12, "playing", hidden = false)
        assertFalse(c.removed)
        assertEquals(1, changes)
        val list = g.list()
        assertEquals(listOf("Sam"), list.map { it.name })
        assertEquals(12L, list[0].driftMs)
        g.report("web-a", "Sam", 8, "playing", hidden = false)
        assertEquals("a repeat report is not a list change", 1, changes)
        assertEquals(8L, g.list()[0].driftMs)
    }

    @Test fun leavingRemovesAtOnce() {
        g.report("web-a", null, null, "connecting", hidden = false)
        g.leave("web-a")
        assertTrue(g.list().isEmpty())
        assertEquals(2, changes)
    }

    @Test fun hostControlsReachTheGuestInTheNextAnswer() {
        g.report("web-a", "Sam", 0, "playing", hidden = false)
        g.setMuted("web-a", true)
        g.setVolume("web-a", 140)
        val c = g.report("web-a", "Sam", 0, "playing", hidden = false)
        assertTrue(c.muted)
        assertEquals(100, c.volume)
        assertEquals("""{"muted":true,"volume":100,"removed":false}""", c.toJson())
    }

    @Test fun removedGuestIsToldAndNotReAdded() {
        g.report("web-a", "Sam", 0, "playing", hidden = false)
        g.remove("web-a")
        assertTrue(g.list().isEmpty())
        assertTrue(g.report("web-a", "Sam", 0, "playing", hidden = false).removed)
        assertTrue(g.list().isEmpty())
        // A new page (new id) can join again.
        assertFalse(g.report("web-b", "Sam", 0, "playing", hidden = false).removed)
    }

    @Test fun silentGuestsAreDroppedButLockedScreensGetLonger() {
        g.report("web-a", "Open page", 0, "playing", hidden = false)
        g.report("web-b", "Locked screen", 0, "playing", hidden = true)
        now += 5_000
        assertEquals(listOf("Locked screen"), g.list().map { it.name })
        now += 11 * 60_000
        assertTrue(g.list().isEmpty())
    }

    @Test fun namesAreCleaned() {
        g.report("web-a", "  Bad\u0000Name\n that is far too long for the host's list  ", null, "x", hidden = false)
        val n = g.list()[0].name
        assertFalse(n.any { it < ' ' })
        assertTrue(n.length <= 40)
        g.report("web-c", "", null, "x", hidden = false)
        assertEquals("Guest", g.list().last().name)
    }
}
