package dev.red74.prismplayer.player

/**
 * Which audio and subtitles a content starts with: Italian audio and no subtitles; when there is no Italian
 * audio, Italian subtitles, or else English subtitles, or else none.
 */
object TrackPolicy {
    const val ITALIAN = "it"
    const val ENGLISH = "en"

    /** "it", "ita", "it-IT", "Italian"... → "it"; the same for English; anything else unchanged (lower case). */
    fun normalize(language: String?): String? {
        val l = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        return when {
            l == "it" || l == "ita" || l.startsWith("it-") || l.startsWith("it_") || l.startsWith("ital") -> ITALIAN
            l == "en" || l == "eng" || l.startsWith("en-") || l.startsWith("en_") || l.startsWith("engl") -> ENGLISH
            else -> l
        }
    }

    fun hasItalianAudio(audioLanguages: List<String?>): Boolean = audioLanguages.any { normalize(it) == ITALIAN }

    /** The subtitle language to switch on, or null for none. */
    fun subtitles(audioLanguages: List<String?>, textLanguages: List<String?>): String? {
        if (hasItalianAudio(audioLanguages)) return null
        val text = textLanguages.map { normalize(it) }
        return when {
            ITALIAN in text -> ITALIAN
            ENGLISH in text -> ENGLISH
            else -> null
        }
    }
}
