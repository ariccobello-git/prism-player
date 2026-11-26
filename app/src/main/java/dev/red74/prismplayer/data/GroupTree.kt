package dev.red74.prismplayer.data

/** How the final list of contents is ordered. [RECENT] shows the entries added last in the list first. */
enum class SortMode { STANDARD, AZ, ZA, RECENT }

fun <T> List<T>.sortedBy(mode: SortMode, title: (T) -> String): List<T> = when (mode) {
    SortMode.STANDARD -> this
    SortMode.AZ -> sortedWith { a, b -> title(a).compareTo(title(b), ignoreCase = true) }
    SortMode.ZA -> sortedWith { a, b -> title(b).compareTo(title(a), ignoreCase = true) }
    SortMode.RECENT -> asReversed()
}

/** Positions of [labels] in the order [mode] shows them: Standard as the list, A-Z, Z-A, latest added first. */
fun displayOrder(labels: List<String>, mode: SortMode): IntArray = labels.indices.toList().sortedBy(mode) { labels[it] }.toIntArray()

/**
 * The groups of a playlist as levels: a main group (for example "SKY CINEMA") and, when the list names several
 * variants of it ("SKY CINEMA [S]", "SKY CINEMA [S+]", or "FILM | AZIONE"), the sub-groups below it.
 * Each sub-group points at one category of the catalog; nothing is copied.
 */
object GroupTree {
    /** The level reached by Back or Left from [level]: contents (2) go to the sub-groups (1) if the group has them, else to the groups (0). */
    fun levelUp(level: Int, hasSubLevel: Boolean): Int = when {
        level >= 2 -> if (hasSubLevel) 1 else 0
        level == 1 -> 0
        else -> -1
    }

    class Sub(val name: String, val category: Int)

    /** [categories] are the catalog categories belonging to this group, in list order. */
    class Main(val name: String, val subs: List<Sub>) {
        val categories: List<Int> get() = subs.map { it.category }
        /** With a single sub-group there is no level to go through: the contents come straight away. */
        val hasSubLevel: Boolean get() = subs.size > 1
    }

    private val trailingTag = Regex("""\s*[\[(]([^\])]{1,12})[\])]\s*$""")
    private val separators = listOf(" | ", "|", " - ", " » ", " > ", ": ")

    /** Splits a group name into its main part and (if any) its variant. */
    fun split(name: String): Pair<String, String?> {
        var base = name.trim()
        var tag: String? = null
        trailingTag.find(base)?.let {
            if (it.range.first > 0) {
                tag = "[" + it.groupValues[1].trim() + "]"
                base = base.substring(0, it.range.first).trim()
            }
        }
        var main = base
        var rest: String? = null
        for (separator in separators) {
            val at = base.indexOf(separator)
            if (at in 1 until base.length - separator.length) {
                main = base.substring(0, at).trim()
                rest = base.substring(at + separator.length).trim().ifEmpty { null }
                break
            }
        }
        val sub = listOfNotNull(rest, tag).joinToString(" ").ifEmpty { null }
        return main.ifEmpty { name.trim() } to sub
    }

    /**
     * [counts] (items per category, same order as [names]) hides the sub-groups with nothing in them; a group whose
     * sub-groups are all empty keeps them, so it never disappears or breaks.
     */
    fun build(names: List<String>, counts: List<Int>? = null, onDemand: Boolean = false): List<Main> {
        val order = LinkedHashMap<String, MutableList<Sub>>()
        names.forEachIndexed { index, name ->
            // The on-demand lists never show the "Live" groups (Primafila and the like): those are in the Live list already.
            if (onDemand && isLiveGroup(name)) return@forEachIndexed
            val (main, sub) = split(name)
            order.getOrPut(main) { ArrayList() } += Sub(sub ?: name.trim().ifEmpty { main }, index)
        }
        return order.map { (main, subs) ->
            val filled = if (counts == null) subs else subs.filter { (counts.getOrNull(it.category) ?: 0) > 0 }
            Main(main, if (filled.isEmpty()) subs else filled)
        }
    }

    private val liveMains = setOf("live", "live tv", "live event", "live events", "eventi live", "diretta", "dirette")

    /** True for a group of live events inside an on-demand list: "LIVE", "LIVE | Primafila", anything called Primafila. */
    fun isLiveGroup(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val text = name.lowercase()
        if ("primafila" in text || "prima fila" in text) return true
        return split(name).first.lowercase().trim() in liveMains
    }
}
