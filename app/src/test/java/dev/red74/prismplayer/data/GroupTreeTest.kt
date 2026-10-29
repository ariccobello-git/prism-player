package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupTreeTest {
    @Test
    fun bracketVariantsBecomeSubGroups() {
        val tree = GroupTree.build(listOf("TOP ITALIA [S]", "SKY CINEMA [S]", "TOP ITALIA [S+]", "PRIMAFILA"))
        assertEquals(listOf("TOP ITALIA", "SKY CINEMA", "PRIMAFILA"), tree.map { it.name })
        val top = tree[0]
        assertTrue(top.hasSubLevel)
        assertEquals(listOf("[S]", "[S+]"), top.subs.map { it.name })
        assertEquals(listOf(0, 2), top.categories)
        assertFalse(tree[2].hasSubLevel)
        assertEquals(listOf(3), tree[2].categories)
    }

    @Test
    fun separatorsSplitMainAndSub() {
        assertEquals("FILM" to "AZIONE", GroupTree.split("FILM | AZIONE"))
        assertEquals("IT" to "Serie TV [HD]", GroupTree.split("IT - Serie TV [HD]"))
        assertEquals("SPORT" to null, GroupTree.split("SPORT"))
        assertEquals("[S]" to null, GroupTree.split("[S]"))
    }

    @Test
    fun everyCategoryIsReachableExactlyOnce() {
        val names = listOf("A | x", "A | y", "B", "", "C [1]", "C [2]", "A | z")
        val tree = GroupTree.build(names)
        assertEquals(names.indices.toList(), tree.flatMap { it.categories }.sorted())
    }

    @Test
    fun sortModes() {
        val list = listOf("banana", "Apple", "cherry")
        assertEquals(list, list.sortedBy(SortMode.STANDARD) { it })
        assertEquals(listOf("Apple", "banana", "cherry"), list.sortedBy(SortMode.AZ) { it })
        assertEquals(listOf("cherry", "banana", "Apple"), list.sortedBy(SortMode.ZA) { it })
        assertEquals(listOf("cherry", "Apple", "banana"), list.sortedBy(SortMode.RECENT) { it })
    }
}
