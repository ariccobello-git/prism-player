package dev.red74.prismplayer.data

import androidx.media3.common.PlaybackException
import dev.red74.prismplayer.data.Availability.Trouble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityTest {
    private val http = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test
    fun httpAnswersAreSortedByWhatTheyMean() {
        assertEquals(Trouble.MISSING, Availability.classify(http, 404))
        assertEquals(Trouble.MISSING, Availability.classify(http, 410))
        assertEquals(Trouble.REFUSED, Availability.classify(http, 403))
        assertEquals(Trouble.REFUSED, Availability.classify(http, null))
        assertEquals(Trouble.SERVER, Availability.classify(http, 503))
        assertEquals(Trouble.NO_ANSWER, Availability.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(Trouble.BAD_STREAM, Availability.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(Trouble.OTHER, Availability.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
    }

    @Test
    fun aRefusalIsMarkedOnlyAfterTheSecondTry() {
        assertTrue(Availability.retries(Trouble.REFUSED))
        assertFalse(Availability.marks(Trouble.REFUSED, afterRetry = false, networkProven = true, preview = false))
        assertTrue(Availability.marks(Trouble.REFUSED, afterRetry = true, networkProven = false, preview = false))
        assertFalse(Availability.marks(Trouble.REFUSED, afterRetry = true, networkProven = true, preview = true))
    }

    @Test
    fun noAnswerCountsOnlyWhileTheNetworkIsProvedToWork() {
        assertFalse(Availability.marks(Trouble.NO_ANSWER, afterRetry = false, networkProven = false, preview = false))
        assertTrue(Availability.marks(Trouble.NO_ANSWER, afterRetry = false, networkProven = true, preview = false))
    }

    @Test
    fun deviceProblemsAndPreviewsNeverMark() {
        assertFalse(Availability.marks(Trouble.OTHER, afterRetry = true, networkProven = true, preview = false))
        assertFalse(Availability.marks(Trouble.BAD_STREAM, afterRetry = true, networkProven = true, preview = true))
        assertTrue(Availability.marks(Trouble.BAD_STREAM, afterRetry = false, networkProven = false, preview = false))
    }

    @Test
    fun notFoundIsTriedAgainBecauseSomePanelsAnswer404ToUnknownIdentities() {
        assertTrue(Availability.retries(Trouble.MISSING))
        assertFalse(Availability.marks(Trouble.MISSING, afterRetry = false, networkProven = true, preview = false))
        assertTrue(Availability.marks(Trouble.MISSING, afterRetry = true, networkProven = false, preview = false))
        assertFalse(Availability.retries(Trouble.NO_ANSWER))
    }

    @Test
    fun marksExpireAndSurviveStorage() {
        val now = 10_000_000_000L
        val marks = mapOf("a1" to now - 1_000L, "b2" to now - Availability.MARK_TTL_MS - 1L)
        assertEquals(setOf("a1"), Availability.live(marks, now).keys)
        assertEquals(marks, Availability.decode(Availability.encode(marks)))
        assertEquals(emptyMap<String, Long>(), Availability.decode(setOf("broken", "x@notanumber")))
    }
}
