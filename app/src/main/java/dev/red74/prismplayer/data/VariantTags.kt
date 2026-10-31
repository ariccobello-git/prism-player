package dev.red74.prismplayer.data

/**
 * The same film, series or channel often appears several times in a list, and what tells the copies apart (the
 * language, the picture quality) is a tag in the name or in the group: "IT - Avatar [4K]", "Avatar (2009) SUB ITA",
 * "FILM FHD". This reads those tags so the screens can show them as small badges and keep the title short.
 *
 * Only the text the list already gives is read: nothing is asked of the panel or of the stream.
 *
 * Words that are surely a tag (ITA, SUB ITA, 4K, FHD, HEVC...) count anywhere in the name. Short words that could
 * also be part of a title ("IT", "HD", "FR") count only where lists put tags: between brackets, before a separator
 * at the start ("IT - ", "EN | ") or at the end, written in capitals.
 */
object VariantTags {
    /** [title] is the name without the tags it carried (the name itself when nothing was found). */
    class Parsed(val title: String, val badges: List<String>)

    private enum class Kind { LANGUAGE, QUALITY, HDR, CODEC, OTHER }

    private class Tag(val label: String, val kind: Kind, val strong: Boolean, val language: Boolean = false)

    private fun strong(label: String, kind: Kind) = Tag(label, kind, true)
    private fun weak(label: String, kind: Kind) = Tag(label, kind, false)
    private fun code(label: String) = Tag(label, Kind.LANGUAGE, false, language = true)

    private val SUB_ITA = strong("SUB ITA", Kind.LANGUAGE)
    private val SUB = weak("SUB", Kind.LANGUAGE)
    private val FHD = strong("FHD", Kind.QUALITY)
    private val HEVC = strong("HEVC", Kind.CODEC)
    private val HDR = strong("HDR", Kind.HDR)

    private val WORDS: Map<String, Tag> = buildMap {
        put("ITA", strong("ITA", Kind.LANGUAGE))
        // Whole words like "English" are also part of titles ("The English Patient"): they count only as edge tags.
        for (w in listOf("ITALIANO", "ITALIAN")) put(w, weak("ITA", Kind.LANGUAGE))
        for (w in listOf("SUBITA", "SUBITAL", "VOSI")) put(w, SUB_ITA)
        for (w in listOf("SUB", "SUBS", "SUBBED", "VOST", "VOSTFR")) put(w, SUB)
        put("ENG", strong("ENG", Kind.LANGUAGE))
        put("ENGLISH", weak("ENG", Kind.LANGUAGE))
        put("MULTI", weak("MULTI", Kind.LANGUAGE))
        for (w in listOf("MULTISUB", "MULTIAUDIO", "MULTILANG")) put(w, strong("MULTI", Kind.LANGUAGE))
        for (w in listOf("FRENCH", "FRANCAIS")) put(w, weak("FRA", Kind.LANGUAGE))
        for (w in listOf("SPANISH", "ESPANOL")) put(w, weak("SPA", Kind.LANGUAGE))
        for (w in listOf("GERMAN", "DEUTSCH")) put(w, weak("GER", Kind.LANGUAGE))
        put("IT", code("ITA"))
        for (w in listOf("EN", "GB", "UK", "US")) put(w, code("ENG"))
        for (w in listOf("FR", "FRA")) put(w, code("FRA"))
        for (w in listOf("ES", "ESP", "SPA")) put(w, code("SPA"))
        for (w in listOf("DE", "GER", "DEU")) put(w, code("GER"))
        for (w in listOf("PT", "POR")) put(w, code("POR"))
        put("VO", code("VO"))

        for (w in listOf("4K", "UHD", "2160P", "2160")) put(w, strong("4K", Kind.QUALITY))
        for (w in listOf("FHD", "FULLHD", "1080P", "1080I")) put(w, FHD)
        put("1080", weak("FHD", Kind.QUALITY))
        put("720P", strong("HD", Kind.QUALITY))
        put("HD", weak("HD", Kind.QUALITY))
        for (w in listOf("480P", "576P")) put(w, strong("SD", Kind.QUALITY))
        put("SD", weak("SD", Kind.QUALITY))
        for (w in listOf("HEVC", "H265", "X265")) put(w, HEVC)
        put("AV1", strong("AV1", Kind.CODEC))
        for (w in listOf("HDR", "HDR10", "HDR10PLUS", "DOVI")) put(w, HDR)
        put("DV", weak("HDR", Kind.HDR))
        put("3D", strong("3D", Kind.OTHER))
        for (w in listOf("HDCAM", "CAMRIP")) put(w, strong("CAM", Kind.OTHER))
        put("CAM", weak("CAM", Kind.OTHER))
        for (w in listOf("50FPS", "60FPS")) put(w, strong(w.dropLast(3) + " FPS", Kind.OTHER))
    }

    /** Two words that make one tag: "SUB ITA", "ITA SUB", "FULL HD", "H 265" (from H.265), "DOLBY VISION". */
    private fun pair(first: String, second: String): Tag? = when {
        first == "SUB" && second == "ITA" -> SUB_ITA
        first == "ITA" && (second == "SUB" || second == "SUBS") -> SUB_ITA
        first == "FULL" && second == "HD" -> FHD
        first == "H" && second == "265" -> HEVC
        first == "DOLBY" && second == "VISION" -> HDR
        first == "MULTI" && (second == "AUDIO" || second == "SUB" || second == "LANG") -> strong("MULTI", Kind.LANGUAGE)
        else -> null
    }

    private const val SEPARATORS = "-|:»>/•~"
    private const val OPEN = "[({"
    private const val CLOSE = "])}"

    private class Word(val start: Int, val end: Int, val upper: String, val capitals: Boolean, val bracket: Int)

    private val cache = object : LinkedHashMap<String, Parsed>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?): Boolean = size > 1500
    }

    /** Tags of a film, series or channel name, and the name without them. Cheap, and remembered for the screens. */
    fun of(name: String): Parsed {
        synchronized(cache) { cache[name]?.let { return it } }
        val parsed = parse(name)
        synchronized(cache) { cache[name] = parsed }
        return parsed
    }

    /** Tags of a group ("FILM 4K", "SERIE TV | SUB ITA"). */
    fun ofGroup(group: String?): List<String> = if (group.isNullOrBlank()) emptyList() else of(group).badges

    /**
     * Badges of the groups seen together on one screen (a search, "Tutti", the favourites): a tag every group has
     * ("ITA" in an Italian list) tells nothing apart, so only the tags some groups have and others do not are kept.
     */
    fun distinctGroupBadges(groups: Collection<String?>): Map<String, List<String>> {
        val names = groups.mapNotNullTo(LinkedHashSet()) { it?.takeIf { g -> g.isNotBlank() } }
        if (names.size < 2) return emptyMap()
        val tags = names.associateWith { ofGroup(it) }
        val common = tags.values.reduce { acc, list -> acc.filter { it in list } }.toSet()
        return tags.mapValues { (_, list) -> list.filter { it !in common } }.filterValues { it.isNotEmpty() }
    }

    /** [first] then the tags of [more] it does not already have, in the usual order (language, quality, ...). */
    fun merge(first: List<String>, vararg more: List<String>): List<String> {
        if (more.all { it.isEmpty() } && first.size < 2) return first
        val all = LinkedHashSet(first)
        for (list in more) all += list
        return ordered(all.toList())
    }

    /**
     * Badges of a page: what the panel measured on the file ([facts]: real resolution, audio language) first, then
     * the name's tags it does not contradict (a name saying "4K" for a file measured FHD keeps only "FHD").
     */
    fun withFacts(facts: List<String>, fromName: List<String>): List<String> {
        if (facts.isEmpty()) return fromName
        val measuredQuality = facts.any { rank(it) == 2 }
        val kept = fromName.filter { !(measuredQuality && rank(it) == 2) }
        return merge(facts, kept)
    }

    /** True for the badges that name a language, which the screens colour differently. */
    fun isLanguage(badge: String): Boolean = badge in LANGUAGE_LABELS

    private val LANGUAGE_LABELS: Set<String> by lazy {
        WORDS.values.filter { it.kind == Kind.LANGUAGE }.map { it.label }.toSet() + setOf("SUB ITA", "MULTI", "JPN", "KOR", "RUS", "ARA", "ALB", "ROU", "POL", "TUR")
    }

    private fun ordered(labels: List<String>): List<String> {
        var list = labels.distinct()
        if ("SUB ITA" in list) list = list.filter { it != "SUB" && it != "ITA" }
        return list.sortedBy { rank(it) }
    }

    private fun rank(label: String): Int = when {
        label == "SUB ITA" || label == "SUB" -> 1
        isLanguage(label) -> 0
        label == "4K" || label == "FHD" || label == "HD" || label == "SD" -> 2
        label == "HDR" -> 3
        label == "HEVC" || label == "AV1" -> 4
        label == "5.1" || label == "7.1" -> 5
        else -> 6
    }

    private fun parse(name: String): Parsed {
        val words = words(name)
        if (words.isEmpty()) return Parsed(name, emptyList())
        val tags = arrayOfNulls<Tag>(words.size)
        val paired = BooleanArray(words.size)
        var i = 0
        while (i < words.size) {
            val next = words.getOrNull(i + 1)
            val two = next?.let { n -> if (n.bracket == words[i].bracket) pair(words[i].upper, n.upper) else null }
            if (two != null) {
                tags[i] = two
                tags[i + 1] = two
                paired[i + 1] = true
                i += 2
            } else {
                tags[i] = WORDS[words[i].upper]
                i++
            }
        }
        val isTag = BooleanArray(words.size)
        val edge = edges(name, words, tags)
        for (index in words.indices) {
            val tag = tags[index] ?: continue
            val word = words[index]
            isTag[index] = when {
                tag.strong -> true
                else -> edge[index] && (word.capitals || word.bracket >= 0)
            }
        }
        // A pair counts only as a whole.
        for (index in words.indices) if (paired[index]) {
            val both = isTag[index - 1] || isTag[index]
            isTag[index - 1] = both
            isTag[index] = both
        }
        if (isTag.none { it }) return Parsed(name, emptyList())

        val labels = ArrayList<String>()
        for (index in words.indices) if (isTag[index] && !paired[index]) labels += tags[index]!!.label
        return Parsed(strip(name, words, isTag), ordered(labels))
    }

    /** Words are runs of letters and digits, each with the bracket group it sits in (-1 for none). */
    private fun words(text: String): List<Word> {
        val out = ArrayList<Word>()
        var bracket = -1
        var groups = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isLetterOrDigit() -> {
                    val start = i
                    while (i < text.length && text[i].isLetterOrDigit()) i++
                    val raw = text.substring(start, i)
                    out += Word(start, i, raw.uppercase(), raw.none { it.isLowerCase() }, bracket)
                    continue
                }
                c in OPEN -> bracket = groups++
                c in CLOSE -> bracket = -1
            }
            i++
        }
        return out
    }

    private fun isYear(word: Word): Boolean =
        word.upper.length == 4 && word.upper.all { it.isDigit() } && (word.upper.startsWith("19") || word.upper.startsWith("20"))

    /**
     * Where a short tag may sit: in a bracket group holding only tags (and years), or in the run of tags at the
     * start or at the end of the name. A run at the start must end with a separator ("IT - Avatar", not "It Follows").
     */
    private fun edges(text: String, words: List<Word>, tags: Array<Tag?>): BooleanArray {
        val edge = BooleanArray(words.size)
        val groupOk = HashMap<Int, Boolean>()
        for (index in words.indices) {
            val b = words[index].bracket
            if (b >= 0) groupOk[b] = (groupOk[b] ?: true) && (tags[index] != null || isYear(words[index]))
        }
        for (index in words.indices) {
            val b = words[index].bracket
            if (b >= 0 && groupOk[b] == true) edge[index] = true
        }
        // Leading run.
        var end = 0
        while (end < words.size && words[end].bracket < 0 && tags[end] != null) end++
        if (end in 1 until words.size) {
            val between = text.substring(words[end - 1].end, words[end].start)
            if (between.any { it in SEPARATORS || it in OPEN }) for (index in 0 until end) edge[index] = true
        }
        // Trailing run (years in it are skipped and kept). Short language codes need a separator before the run.
        var start = words.size
        while (start > 0 && (tags[start - 1] != null || isYear(words[start - 1]))) start--
        if (start in 1 until words.size) {
            val between = text.substring(words[start - 1].end, words[start].start)
            val separated = between.any { it in SEPARATORS || it in CLOSE }
            for (index in start until words.size) {
                val tag = tags[index] ?: continue
                if (!tag.language || separated) edge[index] = true
            }
        }
        return edge
    }

    /** The name without its tags, tidied: no empty brackets, no doubled or dangling separators. */
    private fun strip(text: String, words: List<Word>, isTag: BooleanArray): String {
        val out = StringBuilder(text.length)
        var from = 0
        for (index in words.indices) {
            if (!isTag[index]) continue
            out.append(text, from, words[index].start)
            from = words[index].end
        }
        out.append(text, from, text.length)
        var title = out.toString()
            // "H.265" and similar leave a dot behind.
            .replace(Regex("""(?<=\s)\.(?=\s|$)"""), " ")
        repeat(3) { title = title.replace(EMPTY_BRACKETS, " ") }
        title = title
            .replace(DOUBLE_SEPARATORS) { " " + it.value.trim().first() + " " }
            .replace(Regex("""\s+"""), " ")
            .trim { it.isWhitespace() || it in SEPARATORS || it == ',' || it == '_' || it == '+' || it == '&' }
        return title.ifEmpty { text }
    }

    private val EMPTY_BRACKETS = Regex("""[\[({]\s*[-|:/.,+&_\s]*[\])}]""")
    private val DOUBLE_SEPARATORS = Regex("""\s*[-|:/»•~](?:\s*[-|:/»•~])+\s*""")
}
