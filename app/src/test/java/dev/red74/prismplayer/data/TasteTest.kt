package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {
    @Test
    fun topNeedsTwoPlaysAndOrdersByCount() {
        val taste = TasteModel()
        repeat(5) { taste.bump(ContentType.MOVIE, "Azione") }
        repeat(2) { taste.bump(ContentType.MOVIE, "Drammatici") }
        taste.bump(ContentType.MOVIE, "Una volta sola")
        repeat(9) { taste.bump(ContentType.SERIES, "Serie Crime") }
        assertEquals(listOf("Azione", "Drammatici"), taste.top(ContentType.MOVIE, 5))
        assertEquals(listOf("Azione"), taste.top(ContentType.MOVIE, 1))
        assertEquals(listOf("Serie Crime"), taste.top(ContentType.SERIES, 3))
        assertTrue(taste.top(ContentType.LIVE, 3).isEmpty())
    }

    @Test
    fun blankGroupsAreIgnored() {
        val taste = TasteModel()
        repeat(3) { taste.bump(ContentType.LIVE, null); taste.bump(ContentType.LIVE, "   ") }
        assertTrue(taste.top(ContentType.LIVE, 3).isEmpty())
    }

    @Test
    fun oldHabitsFadeButTheMostWatchedStays() {
        val taste = TasteModel()
        repeat(300) { taste.bump(ContentType.MOVIE, "Azione") }
        repeat(150) { taste.bump(ContentType.MOVIE, "Commedia") }
        assertEquals("Azione", taste.top(ContentType.MOVIE, 1).single())
        assertTrue(taste.encodeCounts().size <= TasteModel.MAX_GROUPS)
    }

    @Test
    fun seenIsCappedKeepingTheNewest() {
        val taste = TasteModel()
        for (i in 0 until TasteModel.MAX_SEEN + 50) taste.markSeen("k$i", i.toLong())
        val seen = taste.seenKeys()
        assertEquals(TasteModel.MAX_SEEN, seen.size)
        assertTrue("k${TasteModel.MAX_SEEN + 49}" in seen)
        assertTrue("k0" !in seen)
    }

    @Test
    fun survivesEncodingAndIgnoresBrokenLines() {
        val taste = TasteModel()
        repeat(3) { taste.bump(ContentType.SERIES, "Crime | con barra") }
        taste.markSeen("abc", 42L)
        val back = TasteModel.decode(taste.encodeCounts() + "rotto" + "x\tSERIES|y", taste.encodeSeen() + "nulla")
        assertEquals(listOf("Crime | con barra"), back.top(ContentType.SERIES, 3))
        assertEquals(setOf("abc"), back.seenKeys())
        assertTrue(TasteModel.decode(null, null).seenKeys().isEmpty())
    }

    @Test
    fun suggestionsSkipSeenAreStableForADayAndChangeTheNext() {
        val pool = (1..50).map { "film$it" }
        val seen = setOf("film1", "film2")
        val today = Suggestions.pick(pool, { it }, seen, 100L, 6)
        assertEquals(6, today.size)
        assertTrue(today.none { it in seen })
        assertEquals(today, Suggestions.pick(pool, { it }, seen, 100L, 6))
        assertTrue(today != Suggestions.pick(pool, { it }, seen, 101L, 6))
        assertTrue(Suggestions.pick(emptyList<String>(), { it }, seen, 1L, 6).isEmpty())
        assertEquals(1, Suggestions.pick(listOf("a", "b"), { it }, setOf("a"), 1L, 6).size)
    }
}
