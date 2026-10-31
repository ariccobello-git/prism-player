package dev.red74.prismplayer.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes titles and descriptions from a playlist or guide safe to show on a TV without losing what they say.
 *
 * Every character the screen can draw is kept: letters of any alphabet, emoji, flags, stars, arrows and other
 * symbols the lists use. A character the TV's fonts cannot draw (it would show as an empty box or a mark in
 * place of the symbol) is first turned into its plain form when it has one ("𝐑𝐚𝐢 ᴴᴰ" becomes "Rai HD",
 * full-width letters become normal ones) and dropped only when it has none. Question marks are removed, because
 * that is how symbols usually end up after a bad conversion of the list.
 */
object TextCleaner {
    /**
     * Whether the screen fonts can draw a character, set by the app at start (Android's Paint.hasGlyph). Left
     * null (unit tests, or before start) every character is taken as drawable.
     */
    @Volatile
    var canDraw: ((String) -> Boolean)? = null

    /** Answers of [canDraw] per character: a list repeats the same few symbols thousands of times. */
    private val drawable = ConcurrentHashMap<Int, Boolean>()

    fun clean(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        var lastWasSpace = true
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == '?'.code || codePoint == 0xFFFD -> Unit
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> {
                    if (!lastWasSpace) out.append(' ')
                    lastWasSpace = true
                }
                isJoiner(codePoint) -> out.appendCodePoint(codePoint)
                !isVisible(codePoint) -> Unit
                // Latin letters, digits and the usual signs: every TV font has them.
                codePoint < 0x250 -> {
                    out.appendCodePoint(codePoint)
                    lastWasSpace = false
                }
                isRegional(codePoint) || isEmoji(codePoint) || draws(codePoint) -> {
                    out.appendCodePoint(codePoint)
                    lastWasSpace = false
                }
                else -> plainForm(codePoint)?.let {
                    out.append(it)
                    lastWasSpace = false
                }
            }
        }
        return out.toString().trim()
    }

    fun cleanOrNull(text: String?): String? = clean(text).ifEmpty { null }

    private fun draws(codePoint: Int): Boolean {
        val check = canDraw ?: return true
        return drawable.getOrPut(codePoint) { runCatching { check(String(Character.toChars(codePoint))) }.getOrDefault(true) }
    }

    /** "𝐑" is "R", "ᴴ" is "H", "Ｒ" is "R": the same letter in a form every font has. Null when there is none. */
    private fun plainForm(codePoint: Int): String? {
        val original = String(Character.toChars(codePoint))
        val plain = Normalizer.normalize(original, Normalizer.Form.NFKC)
        if (plain == original || plain.isBlank()) return null
        var i = 0
        while (i < plain.length) {
            val cp = plain.codePointAt(i)
            i += Character.charCount(cp)
            if (cp >= 0x250 && !draws(cp)) return null
        }
        return plain
    }

    /**
     * Invisible pieces that build one emoji out of several: the joiner (family, professions), the variation
     * selectors (colour or text form), the keycap frame (1️⃣), the tag letters of the Scotland and Wales flags.
     */
    private fun isJoiner(codePoint: Int): Boolean =
        codePoint == 0x200D || codePoint in 0xFE00..0xFE0F || codePoint == 0x20E3 || codePoint in 0xE0020..0xE007F

    /** Control characters, private-use icons and unassigned codes never draw anything readable. */
    private fun isVisible(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> false
        else -> true
    }

    /** Emoji blocks: the app carries its own emoji font (see PrismApp), so they are always drawn. */
    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in 0x1F300..0x1FAFF || codePoint in 0x2600..0x27BF || codePoint in 0x2300..0x23FF || codePoint in 0x2B00..0x2BFF

    /** Regional indicator letters: a pair of them is drawn as a flag. */
    private fun isRegional(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF
}
