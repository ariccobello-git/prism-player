package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedContentsTest {
    @Test
    fun aFewRemovalsAreRecorded() {
        assertFalse(RemovedContents.looksLikeNewAddresses(oldCount = 250_000, removedCount = 1_200))
        assertFalse(RemovedContents.looksLikeNewAddresses(oldCount = 1_000, removedCount = 500))
    }

    @Test
    fun mostOfABigListGoneMeansNewAddresses() {
        assertTrue(RemovedContents.looksLikeNewAddresses(oldCount = 250_000, removedCount = 249_000))
        assertTrue(RemovedContents.looksLikeNewAddresses(oldCount = 1_000, removedCount = 501))
    }

    @Test
    fun smallListsAreAlwaysCompared() {
        assertFalse(RemovedContents.looksLikeNewAddresses(oldCount = 50, removedCount = 50))
    }

    @Test
    fun recordsLastSevenDays() {
        assertEquals(7L * 24 * 60 * 60 * 1000, RemovedContents.KEEP_MS)
    }

    @Test
    fun changesKnowWhenSomethingMoved() {
        assertFalse(ListChanges(emptyList(), 0, 0, emptySet()).changed)
        assertTrue(ListChanges(emptyList(), 0, 3, emptySet()).changed)
        assertTrue(ListChanges(listOf(RemovedItem(ContentType.MOVIE, "Film", null)), 1, 0, emptySet()).changed)
    }
}
