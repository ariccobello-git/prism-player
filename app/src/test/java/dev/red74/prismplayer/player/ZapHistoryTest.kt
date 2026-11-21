package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZapHistoryTest {
    @Test fun nothingBeforeTheFirstChange() {
        assertNull(ZapHistory().target(3, 10))
    }

    @Test fun backAndForthBetweenTwoChannels() {
        val h = ZapHistory()
        h.moved(3, 4)
        assertEquals(3, h.target(4, 10))
        h.moved(4, 3)
        assertEquals(4, h.target(3, 10))
    }

    @Test fun zappingThroughKeepsOnlyTheOneBeforeTheLast() {
        val h = ZapHistory()
        h.moved(1, 2); h.moved(2, 3); h.moved(3, 4)
        assertEquals(3, h.target(4, 10))
    }

    @Test fun sameChannelOrOutOfRangeGivesNothing() {
        val h = ZapHistory()
        h.moved(5, 5)
        assertNull(h.target(5, 10))
        h.moved(7, 2)
        assertNull(h.target(2, 5)) // the list got shorter
        assertEquals(7, h.target(2, 8))
        assertNull(h.target(7, 8)) // already there
        h.clear()
        assertNull(h.target(2, 8))
    }

    @Test fun neighboursWrapAndNeverIncludeTheCurrent() {
        assertEquals(listOf(1, 4), Neighbours.around(0, 5))
        assertEquals(listOf(0, 3), Neighbours.around(4, 5))
        assertEquals(listOf(1), Neighbours.around(0, 2))
        assertEquals(emptyList<Int>(), Neighbours.around(0, 1))
        assertEquals(emptyList<Int>(), Neighbours.around(3, 3))
        assertEquals(listOf(2, 0), Neighbours.around(1, 3))
    }
}
