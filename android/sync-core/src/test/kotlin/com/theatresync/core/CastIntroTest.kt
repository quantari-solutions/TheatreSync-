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

    @Test fun introAsALiveStreamHasNoProgressBarOrTitle() {
        val r = CastIntro.loadRequest("http://192.168.1.82:40000/intro", live = true)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(r).toString()
        assertTrue(json.contains("\"streamType\":\"LIVE\""))
        assertTrue(json.contains("\"contentType\":\"video/mp4\""))
        assertTrue(!json.contains("title"))
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

class AdSkipperParsingTest {
    @org.junit.Test fun parsesLoungeEvents() {
        val text = """152
[[40,["onAdStateChange",{"adState":"1","currentTime":"3","isSkipEnabled":"false"}]]
,[41,["onAdStateChange",{"adState":"1","currentTime":"6","isSkipEnabled":"true"}]]
,[42,["onStateChange",{"currentTime":"12.5","state":"1","duration":"300"}]]
,[43,["noop"]]]"""
        val e = LoungeEvents.parse(text)
        org.junit.Assert.assertEquals(listOf(40L, 41L, 42L, 43L), e.map { it.id })
        org.junit.Assert.assertEquals("true", e[1].fields["isSkipEnabled"])
        org.junit.Assert.assertEquals("12.5", e[2].fields["currentTime"])
        org.junit.Assert.assertEquals("noop", e[3].name)
    }

    @org.junit.Test fun sponsorBlockPicksOurVideoAndSkipsInsideSegments() {
        val json = """[{"videoID":"other123456","segments":[{"segment":[1,50],"category":"sponsor"}]},
            {"videoID":"dQw4w9WgXcQ","segments":[{"segment":[30.5,61.2],"category":"sponsor","UUID":"x"},{"segment":[200,200.4],"category":"selfpromo"}]}]"""
        val segs = SponsorBlock.parse(json, "dQw4w9WgXcQ")
        org.junit.Assert.assertEquals(1, segs.size) // the 0.4 s one is ignored
        org.junit.Assert.assertEquals(30.5, segs[0].startSec, 0.001)
        org.junit.Assert.assertNotNull(SponsorBlock.current(segs, 31.0))
        org.junit.Assert.assertNull(SponsorBlock.current(segs, 61.0)) // already at the end
        org.junit.Assert.assertNull(SponsorBlock.current(segs, 10.0))
        org.junit.Assert.assertEquals(4, SponsorBlock.hashPrefix("dQw4w9WgXcQ").length)
        org.junit.Assert.assertTrue(SponsorBlock.url("dQw4w9WgXcQ").contains("category=sponsor"))
    }
}
