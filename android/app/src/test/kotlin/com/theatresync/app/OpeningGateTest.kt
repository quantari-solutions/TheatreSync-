package com.theatresync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGateTest {
    private val g = OpeningGate(awayMs = 30_000)

    @Test fun playsWhenTheAppIsOpenedButOnlyOnce() {
        assertTrue(g.created(fromNotificationOrShare = false))
        assertFalse(g.started(1_000)) // the same opening, not a second one
    }

    @Test fun playsWhenOpenedAgainAfterUsingOtherApps() {
        g.created(false); g.started(0)
        g.stopped(10_000, screenOn = true, rotating = false, pictureInPicture = false)
        assertTrue(g.started(10_000 + 5 * 60_000))
    }

    @Test fun notAfterAQuickSwitch() {
        g.created(false); g.started(0)
        g.stopped(10_000, screenOn = true, rotating = false, pictureInPicture = false)
        assertFalse(g.started(20_000))
    }

    @Test fun notWhenThePhoneJustWentIdleWithTheAppOpen() {
        g.created(false); g.started(0)
        g.stopped(10_000, screenOn = false, rotating = false, pictureInPicture = false) // screen timed out
        assertFalse(g.started(10_000 + 60 * 60_000))
    }

    @Test fun notFromTheNotificationOrAShare() {
        assertFalse(g.created(fromNotificationOrShare = true))
        g.started(0)
        g.stopped(10_000, screenOn = true, rotating = false, pictureInPicture = false)
        g.newIntent(fromNotificationOrShare = true)
        assertFalse(g.started(10_000 + 5 * 60_000))
    }

    @Test fun notForRotationOrPictureInPicture() {
        g.created(false); g.started(0)
        g.stopped(10_000, screenOn = true, rotating = true, pictureInPicture = false)
        assertFalse(g.started(100_000))
        g.stopped(200_000, screenOn = true, rotating = false, pictureInPicture = true)
        assertFalse(g.started(400_000))
    }

    @Test fun everyReturnAfterBeingAwayPlaysAgain() {
        g.created(false); g.started(0)
        repeat(3) { i ->
            val t = (i + 1) * 1_000_000L
            g.stopped(t, screenOn = true, rotating = false, pictureInPicture = false)
            assertTrue(g.started(t + 60_000))
        }
    }
}
