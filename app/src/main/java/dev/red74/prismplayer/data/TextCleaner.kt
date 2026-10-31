package dev.red74.prismplayer.data

/**
 * Makes titles and descriptions from a playlist or guide safe to show on a TV: symbols the screen font cannot draw
 * (emoji, flags, dingbats, broken-encoding marks) would appear as boxes or question marks, so they are dropped and
 * only the readable text is kept. Question marks are removed as well, because that is how those symbols usually
 * end up after a bad conversion.
 */
object TextCleaner {
    /**
     * Like [clean], but a country flag becomes its two letters in brackets ("🇮🇹 Avatar" is "[IT] Avatar"): in film
     * and series names a flag is often the only sign of the language (see [VariantTags]).
     */
    fun cleanName(text: String?): String = clean(text, flags = true)

    fun clean(text: String?): String = clean(text, flags = false)

    private fun clean(text: String?, flags: Boolean): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        var lastWasSpace = true
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == '?'.code || codePoint == 0xFFFD -> Unit
                flags && isRegional(codePoint) && index < text.length && isRegional(text.codePointAt(index)) -> {
                    val second = text.codePointAt(index)
                    index += Character.charCount(second)
                    if (!lastWasSpace) out.append(' ')
                    out.append('[').append('A' + (codePoint - REGIONAL_A)).append('A' + (second - REGIONAL_A)).append("] ")
                    lastWasSpace = true
                }
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> {
                    if (!lastWasSpace) out.append(' ')
                    lastWasSpace = true
                }
                keep(codePoint) -> {
                    out.appendCodePoint(codePoint)
                    lastWasSpace = false
                }
                else -> Unit
            }
        }
        return out.toString().trim()
    }

    fun cleanOrNull(text: String?): String? = clean(text).ifEmpty { null }

    fun cleanNameOrNull(text: String?): String? = cleanName(text).ifEmpty { null }

    /** Regional indicator letters: a pair of them is drawn as a flag. */
    private const val REGIONAL_A = 0x1F1E6
    private fun isRegional(codePoint: Int): Boolean = codePoint in REGIONAL_A..(REGIONAL_A + 25)

    private fun keep(codePoint: Int): Boolean {
        if (Character.isLetterOrDigit(codePoint)) return true
        // Combining accents belong to the letter they follow.
        return when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK -> true
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.CURRENCY_SYMBOL -> true
            Character.MATH_SYMBOL -> codePoint == '+'.code || codePoint == '='.code || codePoint == '|'.code
            else -> false
        }
    }
}
