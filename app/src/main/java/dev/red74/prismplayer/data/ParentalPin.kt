package dev.red74.prismplayer.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A four-digit PIN that keeps the adult groups of the lists closed. Only a salted hash is stored, never the digits.
 * After a right PIN the groups stay open for [OPEN_MS], then they close again.
 */
object ParentalPin {
    const val LENGTH = 4
    const val OPEN_MS = 15L * 60_000L

    private var prefs: SharedPreferences? = null
    private var openUntil = 0L

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("parental_pin", Context.MODE_PRIVATE)
    }

    val isSet: Boolean get() = prefs?.getString("hash", null) != null

    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt|$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Keeps [pin] (the old one is replaced). False when it is not four digits. */
    fun set(pin: String): Boolean {
        val store = prefs ?: return false
        if (!isValid(pin)) return false
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        store.edit().putString("salt", salt).putString("hash", hash(salt, pin)).apply()
        openUntil = 0L
        return true
    }

    fun clear() {
        prefs?.edit()?.remove("salt")?.remove("hash")?.apply()
        openUntil = 0L
    }

    fun check(pin: String): Boolean {
        val store = prefs ?: return false
        val salt = store.getString("salt", null) ?: return false
        val expected = store.getString("hash", null) ?: return false
        return isValid(pin) && hash(salt, pin) == expected
    }

    /** Keeps the groups open for a while after a right PIN. */
    fun openFor(nowMs: Long = System.currentTimeMillis()) {
        openUntil = nowMs + OPEN_MS
    }

    fun closeNow() {
        openUntil = 0L
    }

    /** True when a group named [name] must be asked for the PIN before it opens. */
    fun locks(name: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        isSet && nowMs >= openUntil && isAdult(name)

    private val adultWords = Regex("""(^|[^a-z0-9])(xxx|adult|adulti|adults|adulto|porn|porno|sexy|erotic|erotica|erotico|18\+|\+18|18plus)([^a-z0-9]|$)""")

    /** Group names that are for adults only. */
    fun isAdult(name: String): Boolean = adultWords.containsMatchIn(name.lowercase())
}
