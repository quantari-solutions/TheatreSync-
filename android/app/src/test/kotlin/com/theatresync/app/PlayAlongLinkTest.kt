package com.theatresync.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayAlongLinkTest {
    @Test fun recognisesSharedTitles() {
        assertEquals("netflix", PlayAlong.appForLink("Watch Stranger Things on Netflix: https://www.netflix.com/title/80057281"))
        assertEquals("prime", PlayAlong.appForLink("Check out The Boys on Prime Video https://app.primevideo.com/detail?gti=amzn1.dv.gti.123"))
        assertEquals("prime", PlayAlong.appForLink("https://www.amazon.com/gp/video/detail/B07FN1MZBH"))
        assertNull(PlayAlong.appForLink("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("https://www.netflix.com/title/80057281", PlayAlong.linkIn("Watch on Netflix: https://www.netflix.com/title/80057281"))
    }

    @Test fun tvAppNames() {
        assertEquals("netflix", PlayAlong.appFor("Netflix"))
        assertEquals("prime", PlayAlong.appFor("Prime Video"))
        assertNull(PlayAlong.appFor("YouTube"))
    }
}
