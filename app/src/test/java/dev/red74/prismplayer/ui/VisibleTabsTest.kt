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

    @Test
    fun anOnDemandListHasNoLiveButtonEvenWithAFewLiveEntries() {
        assertEquals(false, showsLiveTab(0, hasMovies = true, hasSeries = true))
        assertEquals(false, showsLiveTab(40, hasMovies = true, hasSeries = false))
        assertEquals(true, showsLiveTab(5_000, hasMovies = true, hasSeries = true))
        assertEquals(true, showsLiveTab(12, hasMovies = false, hasSeries = false))
        assertEquals(
            listOf(LibraryTab.MOVIES, LibraryTab.SERIES, LibraryTab.FAVORITES),
            visibleTabs(true, true, hasLive = false).map { it.first },
        )
    }
}
