package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlatformsTest {
    @Test
    fun famousPlatformGroupsAreRecognisedWhateverTheWriting() {
        for (g in listOf("NETFLIX", "Film | Netflix 4K", "AMAZON PRIME VIDEO", "Disney+", "DISNEY PLUS FILM", "Apple TV+", "HBO MAX", "Paramount+", "Serie TV - Prime Video")) {
            assertTrue(g, VodPlatforms.isPlatform(g))
        }
    }

    @Test
    fun otherGroupsAndPartialWordsAreNot() {
        for (g in listOf("Azione", "FILM ITALIANI", "Primeval", "Maximum Action", "Netflixx", "")) {
            assertFalse(g, VodPlatforms.isPlatform(g))
        }
        assertEquals(1, VodPlatforms.rank(null))
    }

    @Test
    fun sortingByRankPutsPlatformsFirstAndKeepsTheNewestFirstInsideEach() {
        val groups = listOf("Azione", "Netflix", "Commedia", "Disney+", "Drammatico")
        assertEquals(listOf("Netflix", "Disney+", "Azione", "Commedia", "Drammatico"), groups.sortedBy { VodPlatforms.rank(it) })
    }
}
