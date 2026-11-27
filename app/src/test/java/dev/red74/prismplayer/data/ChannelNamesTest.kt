package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Technical decoration of live channel names is dropped for display and for matching the guide. */
class ChannelNamesTest {
    @Test
    fun dropsProviderMarkAndQuality() {
        assertEquals("SKY CINEMA UNO", ChannelNames.clean("[S+] SKY CINEMA UNO FULL HD"))
        assertEquals("SKY CINEMA", ChannelNames.cleanGroup("SKY CINEMA [S+]"))
        assertEquals("Rai 1", ChannelNames.clean("Rai 1 FHD"))
    }

    @Test
    fun plainNamesStayAndNeverEmpty() {
        assertEquals("Rai 3", ChannelNames.clean("Rai 3"))
        assertEquals("[S+]", ChannelNames.clean("[S+]"))
        assertEquals("", ChannelNames.cleanGroup(" ") ?: "")
    }

    @Test
    fun theGuideNameMeetsTheListName() {
        assertEquals(ChannelNames.key("Sky Cinema Uno"), ChannelNames.key("[S+] SKY CINEMA UNO FULL HD"))
    }

    @Test
    fun aChannelWithDecoratedNameFindsItsProgrammes() {
        val programme = Programme("Il Padrino", 0L, 10_000L, null)
        val epg = EpgData(mapOf("sky.uno" to listOf(programme)), mapOf(ChannelNames.key("Sky Cinema Uno")!! to "sky.uno"))
        val channel = Channel("[S+] SKY CINEMA UNO FULL HD", "http://x/1", "SKY CINEMA [S+]")
        assertNotNull(epg.nowNext(channel, 5_000L).now)
        assertEquals("Il Padrino", epg.nowNext(channel, 5_000L).now?.title)
    }
}
