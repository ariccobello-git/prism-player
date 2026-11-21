package dev.red74.prismplayer.data

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsCacheTest {
    private val now = 1_800_000_000_000L
    private val sky = Channel("Sky Sport Uno", "http://panel.example/live/u/p/1.ts", "Sport", "http://logo.example/s.png", "s1", "Sky Uno")
    private val dazn = Channel("DAZN 1", "http://panel.example/live/u/p/2.ts")

    private fun event(title: String, start: Long, end: Long, vararg channels: Channel, kind: HighlightKind = HighlightKind.SPORT, topic: SportTopic? = SportTopic.FOOTBALL) =
        HighlightEvent(kind, title, topic, start, end, 42, channels.toList())

    private fun data(vararg events: HighlightEvent) = HighlightsData(events.groupBy { it.kind })

    @Test
    fun whatIsSavedComesBackWithItsChannelsWhole() {
        val saved = HighlightsCache.encode(data(event("Roma - Lazio", now + 3_600_000, now + 9_000_000, sky, dazn)))
        val e = HighlightsCache.decode(saved, now).events(HighlightKind.SPORT).single()
        assertEquals("Roma - Lazio", e.title)
        assertEquals(SportTopic.FOOTBALL, e.topic)
        assertEquals(42, e.score)
        assertEquals(listOf(sky, dazn), e.channels)
    }

    @Test
    fun overEventsAreDroppedAndTheRestKeepsItsOrder() {
        val saved = HighlightsCache.encode(
            data(
                event("Finita", now - 9_000_000, now - 1_000, sky),
                event("Resta", now - 1_000, now + 5_000_000, dazn),
            ),
        )
        assertEquals(listOf("Resta"), HighlightsCache.decode(saved, now).events(HighlightKind.SPORT).map { it.title })
    }

    @Test
    fun damagedOrForeignTextGivesNothingAndNeverThrows() {
        for (text in listOf(null, "", "garbage", "prism-highlights-2\nnot\tenough", "prism-highlights-2\nSPORT\tx\tnotanumber\t1\t2\tk\tT", "prism-highlights-1\nSPORT\t\t1\t1\t2\tk\tT", "prism-highlights-2\nSPORT\t\t1\t1\t999999999999999\t\tTitle")) {
            assertTrue("$text", HighlightsCache.decode(text, now).isEmpty)
        }
    }

    @Test
    fun titlesAndNamesWithControlCharactersDoNotBreakTheFile() {
        val odd = Channel("Canale\tuno\n", "http://panel.example/live/u/p/3.ts", "Gr\u001fuppo")
        val saved = HighlightsCache.encode(data(event("Roma\t- Lazio\nbis", now + 1_000, now + 5_000_000, odd)))
        val e = HighlightsCache.decode(saved, now).events(HighlightKind.SPORT).single()
        assertFalse(e.title.contains('\n') || e.title.contains('\t'))
        assertEquals("Canaleuno", e.channels.single().name)
    }

    @Test
    fun theFileOnDiskIsSealedAndReadsBack() {
        val dir = File.createTempFile("hlc", "d").apply { delete(); mkdirs() }
        try {
            val box = SecureBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
            val store = HighlightsStore(dir, box)
            val text = HighlightsCache.encode(data(event("Roma - Lazio", now + 1_000, now + 5_000_000, sky)))
            store.write("list-1", text)
            val raw = dir.listFiles()!!.single { it.name.endsWith(".bin") }.readBytes()
            assertFalse("the file must not hold the address in clear", String(raw, Charsets.ISO_8859_1).contains("panel.example"))
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("Roma"))
            assertEquals(text, store.read("list-1"))
            assertNull(store.read("another"))
            store.delete("list-1")
            assertNull(store.read("list-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBigGuideIsReadBackFast() {
        val events = (0 until 360).map { event("Evento $it", now + it * 60_000L, now + it * 60_000L + 7_200_000L, sky, dazn) }
        val saved = HighlightsCache.encode(HighlightsData(mapOf(HighlightKind.SPORT to events)))
        HighlightsCache.decode(saved, now)
        val started = System.nanoTime()
        repeat(20) { HighlightsCache.decode(saved, now) }
        val ms = (System.nanoTime() - started) / 20 / 1_000_000
        assertTrue("reading 360 events took $ms ms", ms < 100)
    }
}
