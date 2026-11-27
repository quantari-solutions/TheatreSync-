package com.theatresync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An update that doesn't take must not loop forever (owner, 2026-11-27). */
class UpdateRetryTest {
    @Test fun carriesOnByItselfOnlyTwice() {
        assertTrue(UpdateRetry.shouldAutoContinue(0))
        assertTrue(UpdateRetry.shouldAutoContinue(1))
        assertFalse(UpdateRetry.shouldAutoContinue(2))
        assertFalse(UpdateRetry.shouldAutoContinue(50))
    }
}
