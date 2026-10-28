package dev.red74.prismplayer.data

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBoxTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val box = SecureBox { key }

    @Test
    fun roundTrips() {
        val plain = "https://example.com/list.m3u?token=secret".toByteArray()
        assertArrayEquals(plain, box.decrypt(box.encrypt(plain)))
    }

    @Test
    fun roundTripsEmptyPayload() {
        assertArrayEquals(ByteArray(0), box.decrypt(box.encrypt(ByteArray(0))))
    }

    @Test
    fun ciphertextHidesPlaintextAndIsRandomised() {
        val plain = "https://example.com/private?token=SUPERSECRET".toByteArray()
        val a = box.encrypt(plain)
        val b = box.encrypt(plain)
        assertFalse(String(a, Charsets.ISO_8859_1).contains("SUPERSECRET"))
        assertFalse("same plaintext must not give same output", a.contentEquals(b))
    }

    @Test
    fun tamperingIsDetected() {
        val sealed = box.encrypt("payload".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertFailsSecurely { box.decrypt(sealed) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val sealed = box.encrypt("payload".toByteArray())
        val other = SecureBox { newKey() }
        assertFailsSecurely { other.decrypt(sealed) }
    }

    @Test
    fun truncatedOrUnknownFormatIsRejected() {
        assertFailsSecurely { box.decrypt(ByteArray(0)) }
        assertFailsSecurely { box.decrypt(ByteArray(10)) }
        val sealed = box.encrypt("payload".toByteArray())
        sealed[0] = 99
        assertFailsSecurely { box.decrypt(sealed) }
    }

    private fun assertFailsSecurely(block: () -> Unit) {
        try {
            block()
        } catch (e: GeneralSecurityException) {
            return
        }
        assertTrue("expected a GeneralSecurityException", false)
    }
}
