package dev.red74.prismplayer.pairing

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Owns the single active pairing code.
 *
 * Rules enforced here (and covered by unit tests):
 * - a code lives [TTL_MS] (3 minutes) from the moment it is created;
 * - a code works exactly once: [consume] invalidates it;
 * - asking for a new session ([newSession]) invalidates the previous code immediately;
 * - after [MAX_FAILED_ATTEMPTS] wrong guesses the active code is burned, so it cannot be brute-forced on the LAN.
 */
class PairingManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Session(val code: String, val expiresAtMs: Long) {
        /** Human-friendly form, e.g. `K7QF-3MWD`. */
        val display: String get() = code.chunked(4).joinToString("-")
    }

    private val lock = Any()
    private var active: Session? = null
    private var failedAttempts = 0

    fun newSession(): Session = synchronized(lock) {
        val session = Session(code = generateCode(), expiresAtMs = clock() + TTL_MS)
        active = session
        failedAttempts = 0
        session
    }

    /** The active session, or null if there is none, it expired, was used or was locked out. */
    fun current(): Session? = synchronized(lock) { activeLocked() }

    fun remainingMs(): Long = synchronized(lock) {
        activeLocked()?.let { (it.expiresAtMs - clock()).coerceAtLeast(0L) } ?: 0L
    }

    /** Checks a code without using it up. Wrong guesses still count toward the lockout. */
    fun verify(candidate: String): Boolean = synchronized(lock) { checkLocked(candidate, consume = false) }

    /** Checks a code and, if correct, invalidates it so it cannot be reused. */
    fun consume(candidate: String): Boolean = synchronized(lock) { checkLocked(candidate, consume = true) }

    fun invalidate() = synchronized(lock) {
        active = null
    }

    private fun activeLocked(): Session? {
        val session = active ?: return null
        if (clock() >= session.expiresAtMs) {
            active = null
            return null
        }
        return session
    }

    private fun checkLocked(candidate: String, consume: Boolean): Boolean {
        val session = activeLocked() ?: return false
        val normalized = normalize(candidate)
        val matches = MessageDigest.isEqual(
            normalized.toByteArray(Charsets.US_ASCII),
            session.code.toByteArray(Charsets.US_ASCII),
        )
        if (matches) {
            if (consume) active = null
            return true
        }
        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) active = null
        return false
    }

    private fun generateCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    private fun normalize(candidate: String): String =
        candidate.uppercase().filter { it in ALPHABET }

    companion object {
        const val TTL_MS = 3 * 60 * 1000L
        const val MAX_FAILED_ATTEMPTS = 5
        const val CODE_LENGTH = 8

        /** 32 symbols without look-alikes (no 0/O, 1/I): 8 symbols = 40 bits of entropy. */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
