package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {
    @Test
    fun everyTypedWordBecomesAWordBeginning() {
        assertEquals("sky* cin*", FtsQuery.of("Sky Cin"))
        assertEquals("rai* 1*", FtsQuery.of("  rai-1 "))
    }

    @Test
    fun operatorsAndQuotesAreNeverPassedThrough() {
        assertEquals("a* or* b*", FtsQuery.of("a OR b"))
        assertEquals("film* not* azione*", FtsQuery.of("\"film\" NOT (azione)*"))
    }

    @Test
    fun keepsAccentedLettersInsideWords() {
        assertEquals("però*", FtsQuery.of("Però!"))
    }

    @Test
    fun nothingSearchableGivesNull() {
        assertNull(FtsQuery.of(""))
        assertNull(FtsQuery.of(" - * \" "))
    }
}
