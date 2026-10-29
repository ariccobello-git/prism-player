package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowListTest {
    @Test
    fun runsMapIndexesToRowsAcrossSeveralCategories() {
        val runs = RowIds.Runs(longArrayOf(10, 50, 100), intArrayOf(3, 0, 2))
        assertEquals(5, runs.size)
        assertEquals(listOf(10L, 11L, 12L, 100L, 101L), (0 until runs.size).map { runs.at(it) })
        assertEquals(listOf(10L to 12L, 100L to 101L), runs.ranges)
        assertEquals(listOf(101L, 100L, 12L, 11L, 10L), RowIds.Reversed(runs).let { r -> (0 until r.size).map { r.at(it) } })
    }

    @Test
    fun readsOnlyThePagesThatAreShown() {
        val asked = ArrayList<LongArray>()
        val list = RowList(RowIds.Runs(longArrayOf(1), intArrayOf(250_000)), pageSize = 64) { ids ->
            asked += ids
            ids.map { "row $it" }
        }
        assertEquals(250_000, list.size)
        assertEquals("row 1", list[0])
        assertEquals("row 64", list[63])
        assertEquals(1, asked.size)
        assertEquals("row 200000", list[199_999])
        assertEquals(2, asked.size)
        assertTrue(asked.all { it.size <= 64 })
    }

    @Test
    fun keepsOnlyAFewPages() {
        var reads = 0
        val list = RowList(RowIds.Explicit(LongArray(1000) { it.toLong() }), pageSize = 10, keepPages = 2) { ids ->
            reads++
            ids.map { it.toInt() }
        }
        list[0]; list[10]; list[20]
        assertEquals(3, reads)
        list[0] // the first page was dropped
        assertEquals(4, reads)
        list[20]
        assertEquals(4, reads)
    }

    @Test
    fun comparesByIdentitySoComparingNeverReadsTheRows() {
        var reads = 0
        fun make() = RowList(RowIds.Runs(longArrayOf(1), intArrayOf(100))) { ids -> reads++; ids.map { it } }
        val a = make()
        val b = make()
        assertEquals(a, a)
        assertNotEquals(a, b)
        assertEquals(0, reads)
    }
}
