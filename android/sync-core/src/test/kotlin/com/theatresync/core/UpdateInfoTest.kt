package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {
    private val sha = "a".repeat(64)

    @Test fun readsTheReleaseFile() {
        val u = UpdateInfo.parse("""{"versionCode":24,"versionName":"0.5.9","apkUrl":"https://example.org/TheatreSync.apk","sha256":"$sha","sizeBytes":27000000,"notes":"Faster YouTube","extra":1}""")!!
        assertEquals(24, u.versionCode)
        assertEquals("Faster YouTube", u.notes)
        assertTrue(u.isNewerThan(23))
        assertFalse(u.isNewerThan(24))
    }

    @Test fun refusesAnythingUnsafeOrBroken() {
        assertNull(UpdateInfo.parse("not json"))
        assertNull(UpdateInfo.parse("""{"versionCode":24,"versionName":"x","apkUrl":"http://insecure/app.apk","sha256":"$sha"}"""))
        assertNull(UpdateInfo.parse("""{"versionCode":24,"versionName":"x","apkUrl":"https://e.org/a.apk","sha256":"short"}"""))
        assertNull(UpdateInfo.parse("""{"versionCode":0,"versionName":"x","apkUrl":"https://e.org/a.apk","sha256":"$sha"}"""))
    }

    @Test fun roundTrips() {
        val u = UpdateInfo(25, "0.6.0", "https://e.org/a.apk", sha, 1, "hi")
        assertEquals(u, UpdateInfo.parse(UpdateInfo.toJson(u)))
    }
}
