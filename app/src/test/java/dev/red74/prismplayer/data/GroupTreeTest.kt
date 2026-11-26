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

    @Test
    fun backAndLeftGoUpOneLevelAtATime() {
        assertEquals(1, GroupTree.levelUp(2, hasSubLevel = true))
        assertEquals(0, GroupTree.levelUp(2, hasSubLevel = false))
        assertEquals(0, GroupTree.levelUp(1, hasSubLevel = true))
        assertEquals(-1, GroupTree.levelUp(0, hasSubLevel = true))
    }

    private val names = listOf("SKY [S]", "SKY [S+]", "SKY [HD]", "VUOTO [A]", "VUOTO [B]")

    @Test
    fun emptySubGroupsAreHiddenAndKeepTheirCategoryIndex() {
        val main = GroupTree.build(names, listOf(5, 0, 3, 0, 0)).first()
        assertEquals(listOf("[S]", "[HD]"), main.subs.map { it.name })
        assertEquals(listOf(0, 2), main.categories)
    }

    @Test
    fun aSingleFilledSubGroupGoesStraightToTheContents() {
        val main = GroupTree.build(names, listOf(5, 0, 0, 0, 0)).first()
        assertFalse(main.hasSubLevel)
        assertEquals(listOf(0), main.categories)
    }

    @Test
    fun aGroupWhoseSubGroupsAreAllEmptyKeepsThem() {
        val tree = GroupTree.build(names, listOf(5, 0, 3, 0, 0))
        val vuoto = tree.first { it.name == "VUOTO" }
        assertEquals(2, vuoto.subs.size)
    }

    @Test
    fun withoutCountsNothingIsHidden() {
        assertEquals(3, GroupTree.build(names).first().subs.size)
    }

    @Test
    fun onDemandListsNeverShowLiveGroups() {
        val groups = listOf("FILM | AZIONE", "LIVE | Primafila", "Live", "PRIMAFILA", "Live Action", "NETFLIX")
        assertEquals(listOf("FILM", "Live Action", "NETFLIX"), GroupTree.build(groups, onDemand = true).map { it.name })
        // the Live list keeps all of its groups
        assertEquals(6, GroupTree.build(groups).size)
        assertTrue(GroupTree.isLiveGroup("LIVE | Primafila"))
        assertFalse(GroupTree.isLiveGroup("Live Action"))
        assertFalse(GroupTree.isLiveGroup(null))
    }
}
