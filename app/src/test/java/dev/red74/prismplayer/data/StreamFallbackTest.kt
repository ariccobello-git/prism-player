package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamFallbackTest {
    private val agents = listOf<String?>(null, "VLC", "okhttp", "Lavf")

    @Before fun clear() = StreamFallback.forget()

    @Test fun tsGivesHls() {
        assertEquals(listOf("http://h:80/live/u/p/123.m3u8"), StreamFallback.alternates("http://h:80/live/u/p/123.ts"))
    }

    @Test fun hlsGivesTs() {
        assertEquals(listOf("http://h/u/p/9.ts"), StreamFallback.alternates("http://h/u/p/9.m3u8"))
    }

    @Test fun noEndingGivesBoth() {
        assertEquals(listOf("http://h/u/p/9.m3u8", "http://h/u/p/9.ts"), StreamFallback.alternates("http://h/u/p/9"))
    }

    @Test fun queryAndFragmentAreKept() {
        assertEquals(listOf("http://h/live/u/p/5.m3u8?x=1#f"), StreamFallback.alternates("http://h/live/u/p/5.ts?x=1#f"))
    }

    @Test fun notALiveXtreamAddressHasNoAlternate() {
        assertTrue(StreamFallback.alternates("http://h/movie/u/p/5.mp4").isEmpty())
        assertTrue(StreamFallback.alternates("http://h/movie/u/p/5.ts").isEmpty())
        assertTrue(StreamFallback.alternates("http://h/series/u/p/5.mkv").isEmpty())
        assertTrue(StreamFallback.alternates("http://h/timeshift/u/p/60/2026-01-01:10-00/5.ts").isEmpty())
        assertTrue(StreamFallback.alternates("http://h/stream/channel.ts").isEmpty())
        assertTrue(StreamFallback.alternates("http://h/5.ts").isEmpty())
        assertTrue(StreamFallback.alternates("rtsp://h/u/p/5.ts").isEmpty())
        assertTrue(StreamFallback.alternates("not a url").isEmpty())
        assertTrue(StreamFallback.alternates("http://h/u/p/5.mp4").isEmpty())
        assertTrue(StreamFallback.alternates("").isEmpty())
    }

    @Test fun planOrder() {
        val plan = StreamFallback.plan("http://h/live/u/p/7.ts", true, null, agents)
        assertEquals(null, plan[0].agent); assertEquals(0, plan[0].format)
        assertEquals(plan[0], plan[1])
        assertEquals(listOf("VLC", "okhttp", "Lavf"), plan.subList(2, 5).map { it.agent })
        // then the other form, with the preferred identity and with one other
        assertEquals("http://h/live/u/p/7.m3u8", plan[5].url)
        assertEquals(1, plan[5].format)
        assertEquals("VLC", plan[6].agent)
        assertEquals(7, plan.size)
    }

    @Test fun filmsGetOnlyTheIdentities() {
        val plan = StreamFallback.plan("http://h/movie/u/p/7.mp4", false, null, agents)
        assertEquals(5, plan.size)
        assertTrue(plan.all { it.url == "http://h/movie/u/p/7.mp4" })
    }

    @Test fun preferredIdentityGoesFirstAndIsNotRepeated() {
        val plan = StreamFallback.plan("http://h/live/u/p/7.ts", true, "VLC", agents)
        assertEquals("VLC", plan[0].agent)
        assertEquals(listOf(null, "okhttp", "Lavf"), plan.subList(2, 5).map { it.agent })
    }

    @Test fun aFormThatWorkedComesFirstNextTime() {
        val url = "http://h/live/u/p/7.ts"
        StreamFallback.remember(url, "http://h/live/u/p/7.m3u8")
        val plan = StreamFallback.plan(url, true, null, agents)
        assertEquals("http://h/live/u/p/7.m3u8", plan[0].url)
        // the original is still tried afterwards
        assertTrue(plan.any { it.url == url })
        // and forgotten when the original works again
        StreamFallback.remember(url, url)
        assertEquals(url, StreamFallback.plan(url, true, null, agents)[0].url)
    }

    @Test fun neverMoreThanOneAddressPerTry() {
        val plan = StreamFallback.plan("http://h/u/p/7", true, null, agents)
        assertEquals(plan.size, plan.size)
        assertEquals(setOf("http://h/u/p/7", "http://h/u/p/7.m3u8", "http://h/u/p/7.ts"), plan.map { it.url }.toSet())
    }
}
