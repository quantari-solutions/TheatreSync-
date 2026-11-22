package com.theatresync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGateTest {
    private val g = OpeningGate()

    @Test fun playsWhenTheAppStartsFresh() = assertTrue(g.created(fromNotificationOrShare = false, restoredFromBackground = false))

    @Test fun notWhenReturningWhileItRunsInTheBackground() = assertFalse(g.started())

    @Test fun notWhenAndroidRebuildsTheScreenOfABackgroundApp() =
        assertFalse(g.created(fromNotificationOrShare = false, restoredFromBackground = true))

    @Test fun notFromTheNotificationOrAShare() = assertFalse(g.created(fromNotificationOrShare = true, restoredFromBackground = false))
}
