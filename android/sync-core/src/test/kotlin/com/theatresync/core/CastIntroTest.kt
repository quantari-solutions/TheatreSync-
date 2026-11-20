package com.theatresync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastIntroTest {
    @Test fun loadRequestIsValidJsonForTheMediaReceiver() {
        val r = CastIntro.loadRequest("http://192.168.1.82:40000/intro")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(r).toString()
        assertTrue(json.contains("\"type\":\"LOAD\""))
        assertTrue(json.contains("\"contentId\":\"http://192.168.1.82:40000/intro\""))
        assertTrue(json.contains("\"autoplay\":true"))
    }

    @Test fun readsPlayerState() {
        assertEquals("PLAYING", CastIntro.playerState("""{"type":"MEDIA_STATUS","status":[{"playerState":"PLAYING","currentTime":1.2}]}"""))
        assertEquals("IDLE", CastIntro.playerState("""{"type":"MEDIA_STATUS","status":[{"playerState":"IDLE","idleReason":"FINISHED"}]}"""))
        assertNull(CastIntro.playerState("""{"type":"MEDIA_STATUS","status":[]}"""))
    }
}

class UpNextTest {
    private val events = """
        [[3,["nowPlaying",{"videoId":"aaaaaaaaaaa","listId":"RDaaaaaaaaaaa","currentIndex":"1","currentTime":"12.1"}]]
        ,[4,["playlistModified",{"videoIds":"zzzzzzzzzzz,aaaaaaaaaaa,bbbbbbbbbbb,ccccccccccc","currentIndex":"1","listId":"RDaaaaaaaaaaa"}]]
        ,[5,["autoplayUpNext",{"videoId":"ddddddddddd"}]]]
    """
    @org.junit.Test fun upNextAfterTheCurrentVideoThenAutoplay() {
        org.junit.Assert.assertEquals(listOf("bbbbbbbbbbb", "ccccccccccc", "ddddddddddd"), UpNext.parse(events))
        org.junit.Assert.assertEquals("RDaaaaaaaaaaa", UpNext.listId(events))
    }
    @org.junit.Test fun singleVideoHasOnlyAutoplay() {
        org.junit.Assert.assertEquals(listOf("ddddddddddd"), UpNext.parse("""[[5,["autoplayUpNext",{"videoId":"ddddddddddd"}]]]"""))
        org.junit.Assert.assertEquals(emptyList<String>(), UpNext.parse("""[[1,["noop"]]]"""))
    }
}
