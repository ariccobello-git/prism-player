package dev.red74.prismplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GroupSortTest {
    private val groups = listOf("Sport", "azione", "Cinema", "Bambini")

    @Test
    fun everyOrderListsEachGroupOnce() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3), displayOrder(groups, SortMode.STANDARD))
        assertArrayEquals(intArrayOf(1, 3, 2, 0), displayOrder(groups, SortMode.AZ))
        assertArrayEquals(intArrayOf(0, 2, 3, 1), displayOrder(groups, SortMode.ZA))
        assertArrayEquals(intArrayOf(3, 2, 1, 0), displayOrder(groups, SortMode.RECENT))
        assertArrayEquals(IntArray(0), displayOrder(emptyList(), SortMode.AZ))
    }
}
