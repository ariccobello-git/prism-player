package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPrefsTest {
    private fun live(name: String, group: String, n: Int) = Channel(name, "http://h/live/u/p/$n.ts", group)
    private val a1 = live("Rai 1", "Italia", 1)
    private val a2 = live("Rai 2", "Italia", 2)
    private val a3 = live("Rai 3", "Italia", 3)
    private val b1 = live("Sky Sport 1", "Sport", 4)
    private val b2 = live("Sky Sport 2", "Sport", 5)
    private val c1 = live("BBC One", "UK", 6)
    private val film = Channel("Il film", "http://h/movie/u/p/9.mp4", "Film", type = ContentType.MOVIE)

    private fun catalog() = CatalogBuilder.build(listOf(a1, a2, a3, b1, b2, c1, film), null, false)
    private fun key(c: Channel) = FavoriteKey.of(c)
    private fun hidden(c: Channel) = HiddenChannel(key(c), c.group.orEmpty(), c.name)

    @Test fun nothingToArrangeGivesTheSameCatalog() {
        val base = catalog()
        assertSame(base, CustomizedCatalog.of(base, ChannelPrefs()))
    }

    @Test fun hiddenGroupDisappearsAndOthersKeepTheirOrder() {
        val c = CustomizedCatalog.of(catalog(), ChannelPrefs().withHiddenGroup("Sport", true))
        assertEquals(listOf("Italia", "UK"), c.live.map { it.name })
        assertEquals(listOf("Rai 1", "Rai 2", "Rai 3"), c.channels(ContentType.LIVE, listOf(0)).map { it.name })
        assertEquals(listOf("BBC One"), c.channels(ContentType.LIVE, listOf(1)).map { it.name })
        // several groups at once
        assertEquals(listOf("Rai 1", "Rai 2", "Rai 3", "BBC One"), c.channels(ContentType.LIVE, listOf(0, 1)).map { it.name })
    }

    @Test fun hiddenChannelDisappearsFromItsGroupAndCountsFollow() {
        val c = CustomizedCatalog.of(catalog(), ChannelPrefs().withHiddenChannel(hidden(a2), true))
        assertEquals(listOf("Rai 1", "Rai 3"), c.channels(ContentType.LIVE, listOf(0)).map { it.name })
        assertEquals(2, c.live[0].items.size)
        assertEquals(2, c.live[1].items.size) // other groups untouched
        assertEquals(listOf("Rai 1", "Rai 3", "Sky Sport 1", "Sky Sport 2"), c.channels(ContentType.LIVE, listOf(0, 1)).map { it.name })
    }

    @Test fun searchAndAllLiveSkipHidden() {
        val prefs = ChannelPrefs().withHiddenChannel(hidden(b1), true).withHiddenGroup("UK", true)
        val c = CustomizedCatalog.of(catalog(), prefs)
        assertEquals(listOf("Sky Sport 2"), c.search(ContentType.LIVE, "sky", 10).map { it.name })
        assertTrue(c.search(ContentType.LIVE, "bbc", 10).isEmpty())
        assertEquals(listOf("Rai 1", "Rai 2", "Rai 3", "Sky Sport 2"), c.allLive().map { it.name })
        // films are never touched
        assertEquals(listOf("Il film"), c.search(ContentType.MOVIE, "film", 10).map { it.name })
    }

    @Test fun customGroupComesFirstWithItsChannelsInTheOrderTheViewerAddedThem() {
        val prefs = ChannelPrefs().withGroup("Mie").withChannelInGroup("Mie", key(c1), true).withChannelInGroup("Mie", key(a2), true)
        val c = CustomizedCatalog.of(catalog(), prefs)
        assertEquals(listOf("★ Mie", "Italia", "Sport", "UK"), c.live.map { it.name })
        assertEquals(listOf("BBC One", "Rai 2"), c.channels(ContentType.LIVE, listOf(0)).map { it.name })
        // the group together with a real one
        assertEquals(listOf("BBC One", "Rai 2", "Sky Sport 1", "Sky Sport 2"), c.channels(ContentType.LIVE, listOf(0, 2)).map { it.name })
        // the real group still gives its channels, through the right index
        assertEquals(listOf("BBC One"), c.channels(ContentType.LIVE, listOf(3)).map { it.name })
    }

    @Test fun emptyOrFullyHiddenCustomGroupIsNotShown() {
        val prefs = ChannelPrefs().withGroup("Vuoto").withGroup("Nascosti").withChannelInGroup("Nascosti", key(a1), true).withHiddenChannel(hidden(a1), true)
        val c = CustomizedCatalog.of(catalog(), prefs)
        assertEquals(listOf("Italia", "Sport", "UK"), c.live.map { it.name })
        assertEquals(listOf("Rai 2", "Rai 3"), c.channels(ContentType.LIVE, listOf(0)).map { it.name })
    }

    @Test fun hidingEveryGroupLeavesAnEmptyLiveTab() {
        val prefs = ChannelPrefs().withHiddenGroup("Italia", true).withHiddenGroup("Sport", true).withHiddenGroup("UK", true)
        val c = CustomizedCatalog.of(catalog(), prefs)
        assertTrue(c.live.isEmpty())
        assertEquals(1, c.movies.size)
        assertFalse(c.isEmpty)
    }

    @Test fun nameIsTidiedForTheGroupTree() {
        assertEquals("Sport Calcio", ChannelPrefs.cleanName("  Sport | Calcio "))
        assertEquals("Sport Calcio", ChannelPrefs.cleanName("Sport - Calcio"))
        assertEquals("Film", ChannelPrefs.cleanName("Film [HD]".replace("HD", "")).trim())
        assertEquals("", ChannelPrefs.cleanName(" : > "))
        assertEquals(24, ChannelPrefs.cleanName("x".repeat(60)).length)
        // the name survives GroupTree whole
        val tree = GroupTree.build(listOf(ChannelPrefs.shownName(ChannelPrefs.cleanName("Le mie: partite - serali"))))
        assertEquals(1, tree.size)
        assertEquals(1, tree[0].subs.size)
    }

    @Test fun limitsAndDuplicates() {
        var p = ChannelPrefs()
        repeat(20) { p = p.withGroup("G$it") }
        assertEquals(ChannelPrefs.MAX_GROUPS, p.groups.size)
        assertEquals(p, p.withGroup("g1")) // same name, any case
        assertEquals(p, p.withGroup("  "))
        var q = ChannelPrefs().withGroup("A")
        repeat(ChannelPrefs.MAX_PER_GROUP + 5) { q = q.withChannelInGroup("A", "k$it", true) }
        assertEquals(ChannelPrefs.MAX_PER_GROUP, q.groups[0].keys.size)
        assertEquals(q, q.withChannelInGroup("A", "k0", true))
        assertEquals(listOf("A"), q.groupsOf("k3"))
        assertEquals(emptyList<String>(), q.withChannelInGroup("A", "k3", false).groupsOf("k3"))
    }

    @Test fun hiddenToggleIsReversible() {
        val h = hidden(a1)
        val on = ChannelPrefs().withHiddenChannel(h, true)
        assertTrue(on.isChannelHidden(h.key))
        assertEquals(on, on.withHiddenChannel(h, true))
        assertTrue(on.withHiddenChannel(h, false).isEmpty)
        assertTrue(ChannelPrefs().withHiddenGroup("X", true).withHiddenGroup("X", false).isEmpty)
    }

    @Test fun encodeDecodeRoundTrip() {
        val p = ChannelPrefs().withHiddenGroup("Sport\tstrano", true).withHiddenChannel(hidden(a1), true)
            .withGroup("Mie").withChannelInGroup("Mie", "k1", true).withChannelInGroup("Mie", "k2", true).withGroup("Vuoto")
        val back = ChannelPrefs.decode(p.encode())
        assertEquals(setOf("Sport strano"), back.hiddenGroups)
        assertEquals(p.hiddenChannels, back.hiddenChannels)
        assertEquals(p.groups, back.groups)
        assertTrue(ChannelPrefs.decode(null).isEmpty)
        assertTrue(ChannelPrefs.decode("").isEmpty)
        assertTrue(ChannelPrefs.decode("garbage\nH\nC\tonly\nG").isEmpty)
    }
}
