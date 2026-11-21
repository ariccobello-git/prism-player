package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsCacheTest {
    private val now = 1_800_000_000_000L
    private val sky = Channel("Sky Sport Uno", "http://panel.example/live/u/p/1.ts", "Sport", tvgId = "s1")
    private val dazn = Channel("DAZN 1", "http://panel.example/live/u/p/2.ts", "Sport", tvgId = "d1")
    private val byKey = listOf(sky, dazn).associateBy { FavoriteKey.of(it) }

    private fun event(title: String, start: Long, end: Long, vararg channels: Channel, kind: HighlightKind = HighlightKind.SPORT, topic: SportTopic? = SportTopic.FOOTBALL) =
        HighlightEvent(kind, title, topic, start, end, 42, channels.toList())

    private fun data(vararg events: HighlightEvent) = HighlightsData(events.groupBy { it.kind })

    @Test
    fun whatIsSavedComesBackWithItsChannels() {
        val saved = HighlightsCache.encode(data(event("Roma - Lazio", now + 3_600_000, now + 9_000_000, sky, dazn)))
        val back = HighlightsCache.decode(saved, now) { byKey[it] }
        val e = back.events(HighlightKind.SPORT).single()
        assertEquals("Roma - Lazio", e.title)
        assertEquals(SportTopic.FOOTBALL, e.topic)
        assertEquals(42, e.score)
        assertEquals(listOf(sky, dazn), e.channels)
    }

    @Test
    fun theFileHoldsNoAddressNoCredentialsAndNoChannelName() {
        val saved = HighlightsCache.encode(data(event("Roma - Lazio", now + 1_000, now + 9_000_000, sky)))
        assertFalse(saved.contains("panel.example"))
        assertFalse(saved.contains("http"))
        assertFalse(saved.contains("Sky Sport"))
    }

    @Test
    fun overEventsAndEventsOfChannelsTheListNoLongerHasAreDropped() {
        val saved = HighlightsCache.encode(
            data(
                event("Finita", now - 9_000_000, now - 1_000, sky),
                event("Canale sparito", now + 1_000, now + 5_000_000, sky),
                event("Resta", now - 1_000, now + 5_000_000, dazn),
            ),
        )
        val back = HighlightsCache.decode(saved, now) { if (it == FavoriteKey.of(dazn)) dazn else null }
        assertEquals(listOf("Resta"), back.events(HighlightKind.SPORT).map { it.title })
    }

    @Test
    fun damagedOrForeignTextGivesNothingAndNeverThrows() {
        for (text in listOf(null, "", "garbage", "prism-highlights-1\nnot\tenough", "prism-highlights-1\nSPORT\tx\tnotanumber\t1\t2\tk\tT", "other-header\nSPORT\t\t1\t1\t2\tk\tT")) {
            assertTrue("$text", HighlightsCache.decode(text, now) { sky }.isEmpty)
        }
    }

    @Test
    fun titlesWithTabsAndNewlinesDoNotBreakTheFile() {
        val saved = HighlightsCache.encode(data(event("Roma\t- Lazio\nbis", now + 1_000, now + 5_000_000, sky)))
        val back = HighlightsCache.decode(saved, now) { byKey[it] }
        assertEquals(1, back.events(HighlightKind.SPORT).size)
        assertFalse(back.events(HighlightKind.SPORT).single().title.contains('\n'))
    }

    @Test
    fun aBigGuideIsReadBackFast() {
        val events = (0 until 360).map { event("Evento $it", now + it * 60_000L, now + it * 60_000L + 7_200_000L, sky, dazn) }
        val saved = HighlightsCache.encode(HighlightsData(mapOf(HighlightKind.SPORT to events)))
        val started = System.nanoTime()
        repeat(20) { HighlightsCache.decode(saved, now) { byKey[it] } }
        val ms = (System.nanoTime() - started) / 20 / 1_000_000
        assertTrue("reading 360 events took $ms ms", ms < 200)
    }
}
