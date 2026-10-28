package dev.red74.prismplayer.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private var now = 1_000_000L
    private val manager = PairingManager(clock = { now })

    @Test
    fun codeHasExpectedShapeAndThreeMinuteLifetime() {
        val session = manager.newSession()
        assertEquals(PairingManager.CODE_LENGTH, session.code.length)
        assertTrue(session.code.all { it in PairingManager.ALPHABET })
        assertEquals(now + 180_000L, session.expiresAtMs)
        assertEquals(180_000L, manager.remainingMs())
        assertEquals(session.code.take(4) + "-" + session.code.drop(4), session.display)
    }

    @Test
    fun codeWorksExactlyOnce() {
        val code = manager.newSession().code
        assertTrue(manager.consume(code))
        assertFalse("a used code must not work again", manager.consume(code))
        assertFalse(manager.verify(code))
        assertNull(manager.current())
    }

    @Test
    fun verifyDoesNotUseUpTheCode() {
        val code = manager.newSession().code
        assertTrue(manager.verify(code))
        assertTrue(manager.verify(code))
        assertTrue(manager.consume(code))
    }

    @Test
    fun codeExpiresAfterThreeMinutes() {
        val code = manager.newSession().code
        now += 179_999L
        assertTrue(manager.verify(code))
        now += 1L
        assertFalse("code must be dead at exactly 3 minutes", manager.verify(code))
        assertFalse(manager.consume(code))
        assertNull(manager.current())
        assertEquals(0L, manager.remainingMs())
    }

    @Test
    fun newRequestInvalidatesThePreviousCode() {
        val first = manager.newSession().code
        val second = manager.newSession().code
        assertNotEquals(first, second)
        assertFalse(manager.consume(first))
        assertTrue(manager.consume(second))
    }

    @Test
    fun newRequestRestartsTheTimer() {
        manager.newSession()
        now += 170_000L
        val fresh = manager.newSession()
        now += 170_000L
        assertTrue(manager.verify(fresh.code))
    }

    @Test
    fun repeatedWrongGuessesBurnTheActiveCode() {
        val code = manager.newSession().code
        repeat(PairingManager.MAX_FAILED_ATTEMPTS) {
            assertFalse(manager.verify("WRONGCOD"))
        }
        assertFalse("lockout must also reject the right code", manager.verify(code))
        assertNull(manager.current())
    }

    @Test
    fun fewerWrongGuessesDoNotLockOut() {
        val code = manager.newSession().code
        repeat(PairingManager.MAX_FAILED_ATTEMPTS - 1) {
            assertFalse(manager.verify("WRONGCOD"))
        }
        assertTrue(manager.consume(code))
    }

    @Test
    fun inputIsNormalisedForManualTyping() {
        val code = manager.newSession().code
        val typed = code.lowercase().chunked(4).joinToString(" - ")
        assertTrue(manager.consume(typed))
    }

    @Test
    fun invalidateKillsTheSession() {
        val code = manager.newSession().code
        assertNotNull(manager.current())
        manager.invalidate()
        assertNull(manager.current())
        assertFalse(manager.consume(code))
    }

    @Test
    fun nothingIsValidWithoutASession() {
        assertFalse(manager.verify("ABCDEFGH"))
        assertFalse(manager.consume("ABCDEFGH"))
        assertEquals(0L, manager.remainingMs())
    }
}
