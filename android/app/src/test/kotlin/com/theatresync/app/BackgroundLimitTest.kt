package com.theatresync.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BackgroundLimitTest {
    private val tz = TimeZone.getTimeZone("UTC")
    private val h = 3_600_000L

    @Test fun hours() {
        val s = BackgroundLimit.Setting(BackgroundLimit.Mode.HOURS, 4.0)
        assertFalse(BackgroundLimit.expired(s, 0, 3 * h, tz))
        assertTrue(BackgroundLimit.expired(s, 0, 4 * h, tz))
    }

    @Test fun always() {
        assertFalse(BackgroundLimit.expired(BackgroundLimit.Setting(BackgroundLimit.Mode.ALWAYS), 0, 1000 * h, tz))
    }

    @Test fun untilATime() {
        val s = BackgroundLimit.Setting(BackgroundLimit.Mode.UNTIL, until = "23:00")
        val idle = 20 * h // 20:00 UTC on day 0
        assertFalse(BackgroundLimit.expired(s, idle, 22 * h, tz))
        assertTrue(BackgroundLimit.expired(s, idle, 23 * h, tz))
        // Left idle at 23:30: runs until 23:00 the next day.
        assertFalse(BackgroundLimit.expired(s, 23 * h + h / 2, 30 * h, tz))
        assertTrue(BackgroundLimit.expired(s, 23 * h + h / 2, 47 * h, tz))
    }
}
