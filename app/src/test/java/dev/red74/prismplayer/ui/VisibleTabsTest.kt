package dev.red74.prismplayer.ui

import dev.red74.prismplayer.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleTabsTest {
    private fun tabs(movies: Boolean, series: Boolean) = visibleTabs(movies, series).map { it.first }

    @Test
    fun emptyFilmAndSerieTabsAreHidden() {
        assertEquals(listOf(LibraryTab.LIVE, LibraryTab.FAVORITES), tabs(false, false))
    }

    @Test
    fun onlyTheTabsWithContentAreShown() {
        assertEquals(listOf(LibraryTab.LIVE, LibraryTab.MOVIES, LibraryTab.FAVORITES), tabs(true, false))
        assertEquals(listOf(LibraryTab.LIVE, LibraryTab.SERIES, LibraryTab.FAVORITES), tabs(false, true))
    }

    @Test
    fun fullListShowsEverythingInOrder() {
        assertEquals(listOf(LibraryTab.LIVE, LibraryTab.MOVIES, LibraryTab.SERIES, LibraryTab.FAVORITES), tabs(true, true))
    }
}
