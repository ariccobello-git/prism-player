package dev.red74.prismplayer.data

/** How the final list of contents is ordered. [RECENT] shows the entries added last in the list first. */
enum class SortMode { STANDARD, AZ, ZA, RECENT }

fun <T> List<T>.sortedBy(mode: SortMode, title: (T) -> String): List<T> = when (mode) {
    SortMode.STANDARD -> this
    SortMode.AZ -> sortedWith { a, b -> title(a).compareTo(title(b), ignoreCase = true) }
    SortMode.ZA -> sortedWith { a, b -> title(b).compareTo(title(a), ignoreCase = true) }
    SortMode.RECENT -> asReversed()
}

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

    fun build(names: List<String>): List<Main> {
        val order = LinkedHashMap<String, MutableList<Sub>>()
        names.forEachIndexed { index, name ->
            val (main, sub) = split(name)
            order.getOrPut(main) { ArrayList() } += Sub(sub ?: name.trim().ifEmpty { main }, index)
        }
        return order.map { (main, subs) -> Main(main, subs) }
    }
}
