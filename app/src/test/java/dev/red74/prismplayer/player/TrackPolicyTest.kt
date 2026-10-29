package dev.red74.prismplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPolicyTest {
    @Test
    fun italianAudioMeansNoSubtitles() {
        assertNull(TrackPolicy.subtitles(listOf("en", "ita"), listOf("it", "en")))
        assertNull(TrackPolicy.subtitles(listOf("it-IT"), listOf("en")))
    }

    @Test
    fun withoutItalianAudioItalianSubtitlesComeFirstThenEnglish() {
        assertEquals("it", TrackPolicy.subtitles(listOf("en"), listOf("en", "ita")))
        assertEquals("en", TrackPolicy.subtitles(listOf("de"), listOf("fr", "eng")))
        assertNull(TrackPolicy.subtitles(listOf("de"), listOf("fr", null, "und")))
        assertNull(TrackPolicy.subtitles(emptyList(), emptyList()))
    }

    @Test
    fun languageNamesAreNormalised() {
        assertEquals("it", TrackPolicy.normalize("Italiano"))
        assertEquals("en", TrackPolicy.normalize("en_US"))
        assertNull(TrackPolicy.normalize("und"))
        assertEquals("de", TrackPolicy.normalize("DE"))
    }
}
