package dev.red74.prismplayer.data

import androidx.media3.common.PlaybackException
import dev.red74.prismplayer.player.AudioSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of the code added in v1.0.13 to v1.0.20: odd texts, list-size limits, sound errors. */
class RecentEdgeCasesTest {
    @After
    fun reset() {
        TextCleaner.canDraw = null
        AudioSafety.on = false
    }

    @Test
    fun brokenTextNeverCrashesTheCleaner() {
        assertEquals("", TextCleaner.clean(""))
        assertEquals("", TextCleaner.clean("   "))
        // A lone half of an emoji (bad conversion of a list) is dropped, the rest stays.
        assertEquals("ab", TextCleaner.clean("a\uD83Db"))
        assertEquals("ab", TextCleaner.clean("a\uDE00b"))
        assertEquals("Rai", TextCleaner.clean("Rai\uD83D"))
    }

    @Test
    fun loneRegionalLettersAtTheEdgesAreLetters() {
        assertEquals("K", TextCleaner.clean("🇰"))
        assertEquals("K4", TextCleaner.clean("🇰4"))
        assertEquals("4K", TextCleaner.clean("4🇰"))
    }

    @Test
    fun flagsStayFlagsWhateverTheirPosition() {
        assertEquals("🇮🇹", TextCleaner.clean("🇮🇹"))
        assertEquals("Rai 🇮🇹", TextCleaner.clean("Rai 🇮🇹"))
        assertEquals("🇮🇹 Rai", TextCleaner.clean("🇮🇹 Rai"))
    }

    @Test
    fun letterOfKnowsOnlyLetterLikeSymbols() {
        assertEquals("K", TextCleaner.letterOf(0x1F1F0))
        assertEquals("K", TextCleaner.letterOf(0x1F17A))
        assertEquals("1", TextCleaner.letterOf(0x2776))
        assertEquals(null, TextCleaner.letterOf('a'.code))
        assertEquals(null, TextCleaner.letterOf(0x1F600))
    }

    @Test
    fun aListIsNeverCalledChangedAddressesJustBelowTheLimits() {
        // Exactly half gone is still compared; one more than half is not.
        assertFalse(RemovedContents.looksLikeNewAddresses(oldCount = 200, removedCount = 100))
        assertTrue(RemovedContents.looksLikeNewAddresses(oldCount = 200, removedCount = 101))
        // Under 200 entries every removal counts.
        assertFalse(RemovedContents.looksLikeNewAddresses(oldCount = 199, removedCount = 199))
        // Nothing removed from a big list.
        assertFalse(RemovedContents.looksLikeNewAddresses(oldCount = 500_000, removedCount = 0))
    }

    @Test
    fun removedRecordsStayWithinTheirLimits() {
        assertTrue(RemovedContents.MAX_ITEMS <= RemovedContents.MAX_STORED)
        assertTrue(RemovedContents.KEEP_MS > 0)
    }

    @Test
    fun bothSoundErrorsSwitchOnceAndOthersNever() {
        assertTrue(AudioSafety.isAudioOutputError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertTrue(AudioSafety.isAudioOutputError(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertFalse(AudioSafety.isAudioOutputError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(AudioSafety.switchOn(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertFalse(AudioSafety.switchOn(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
    }
}
