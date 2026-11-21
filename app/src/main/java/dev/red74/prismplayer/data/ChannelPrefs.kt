package dev.red74.prismplayer.data

/** A channel the viewer hid: its hashed key ([FavoriteKey]), its group and its name (to show it in the list of hidden ones). */
data class HiddenChannel(val key: String, val group: String, val name: String)

/** A group made by the viewer: a name and the keys of the live channels in it. */
data class CustomGroup(val name: String, val keys: List<String>)

/**
 * How one list's live channels are arranged by the viewer: groups hidden, single channels hidden, and groups of the
 * viewer's own ("My channels"), shown first in the Live tab. Only names and hashed keys are kept, never addresses.
 */
data class ChannelPrefs(
    val hiddenGroups: Set<String> = emptySet(),
    val hiddenChannels: List<HiddenChannel> = emptyList(),
    val groups: List<CustomGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = hiddenGroups.isEmpty() && hiddenChannels.isEmpty() && groups.isEmpty()

    fun isChannelHidden(key: String): Boolean = hiddenChannels.any { it.key == key }

    fun withHiddenGroup(name: String, hidden: Boolean): ChannelPrefs =
        copy(hiddenGroups = if (hidden) hiddenGroups + name else hiddenGroups - name)

    fun withHiddenChannel(channel: HiddenChannel, hidden: Boolean): ChannelPrefs = copy(
        hiddenChannels = if (hidden) {
            if (isChannelHidden(channel.key)) hiddenChannels else (hiddenChannels + channel).takeLast(MAX_HIDDEN)
        } else hiddenChannels.filter { it.key != channel.key },
    )

    /** Adds an empty group; the name is tidied (see [cleanName]); a repeated, empty or the 13th group is ignored. */
    fun withGroup(name: String): ChannelPrefs {
        val clean = cleanName(name)
        if (clean.isEmpty() || groups.size >= MAX_GROUPS || groups.any { it.name.equals(clean, ignoreCase = true) }) return this
        return copy(groups = groups + CustomGroup(clean, emptyList()))
    }

    fun withoutGroup(name: String): ChannelPrefs = copy(groups = groups.filter { it.name != name })

    /** Puts a channel in (or takes it out of) the group [name]; a group holds at most [MAX_PER_GROUP] channels. */
    fun withChannelInGroup(name: String, key: String, member: Boolean): ChannelPrefs = copy(
        groups = groups.map { g ->
            when {
                g.name != name -> g
                member -> if (key in g.keys || g.keys.size >= MAX_PER_GROUP) g else g.copy(keys = g.keys + key)
                else -> g.copy(keys = g.keys - key)
            }
        },
    )

    fun groupsOf(key: String): List<String> = groups.filter { key in it.keys }.map { it.name }

    // One record per line: H<TAB>group, C<TAB>key<TAB>group<TAB>name, G<TAB>name<TAB>key,key...
    fun encode(): String = buildList {
        hiddenGroups.sorted().forEach { add("H\t" + one(it)) }
        hiddenChannels.forEach { add("C\t${it.key}\t${one(it.group)}\t${one(it.name)}") }
        groups.forEach { add("G\t${one(it.name)}\t${it.keys.joinToString(",")}") }
    }.joinToString("\n")

    companion object {
        const val MAX_GROUPS = 12
        const val MAX_PER_GROUP = 500
        const val MAX_HIDDEN = 1000
        const val MAX_NAME = 24

        /** What the group list shows in front of a group of the viewer's own: it cannot be mistaken for a group of the list. */
        const val MARK = "★ "

        /**
         * A name the group tree keeps whole: no separators (`|`, ` - `, `:`, `>`, `»`), no brackets, one space between
         * words, at most [MAX_NAME] letters.
         */
        fun cleanName(text: String): String = text
            .replace(Regex("""[|:>»\[\]()\t\n\r]"""), " ")
            .replace(" - ", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_NAME)
            .trim()

        /** The name a group of the viewer's own has in the list of groups. */
        fun shownName(name: String): String = MARK + name

        fun decode(text: String?): ChannelPrefs {
            if (text.isNullOrEmpty()) return ChannelPrefs()
            val hiddenGroups = LinkedHashSet<String>()
            val hidden = ArrayList<HiddenChannel>()
            val groups = ArrayList<CustomGroup>()
            for (line in text.split('\n')) {
                val f = line.split('\t')
                when (f.firstOrNull()) {
                    "H" -> if (f.size >= 2 && f[1].isNotEmpty()) hiddenGroups += f[1]
                    "C" -> if (f.size >= 4 && f[1].isNotEmpty() && hidden.none { it.key == f[1] }) hidden += HiddenChannel(f[1], f[2], f.drop(3).joinToString(" "))
                    "G" -> if (f.size >= 2) {
                        val name = cleanName(f[1])
                        if (name.isNotEmpty() && groups.none { it.name == name } && groups.size < MAX_GROUPS) {
                            val keys = f.getOrNull(2).orEmpty().split(',').filter { it.isNotEmpty() }.distinct().take(MAX_PER_GROUP)
                            groups += CustomGroup(name, keys)
                        }
                    }
                }
            }
            return ChannelPrefs(hiddenGroups, hidden.takeLast(MAX_HIDDEN), groups)
        }

        private fun one(text: String): String = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}

/**
 * A list as the viewer arranged it ([ChannelPrefs]): the groups of the viewer's own first, then the groups of the
 * list without the hidden ones, and no hidden channel anywhere in the Live tab. Films and series are left as they are.
 *
 * Everything else is answered by the catalog underneath: nothing is copied except the few groups that hold a hidden
 * channel (a live group is small). It is built off the main thread (the groups of the viewer's own are looked up in
 * the list database) and never closes the catalog underneath, which its owner does.
 */
class CustomizedCatalog private constructor(
    private val base: Catalog,
    private val hiddenKeys: Set<String>,
    private val hiddenGroups: Set<String>,
    live: List<Category>,
    /** For each category of [live]: its number in the catalog underneath, or -1 for a group of the viewer's own. */
    private val source: IntArray,
    /** True for a category whose contents were cut here (they differ from the catalog underneath). */
    private val cut: BooleanArray,
) : Catalog(live, base.movies, base.series, base.epgUrl, base.truncated, base.totalEntries) {

    override fun channels(type: ContentType, categories: List<Int>): List<Channel> {
        if (type != ContentType.LIVE) return base.channels(type, categories)
        if (categories.size == 1) return live[categories[0]].items
        if (categories.none { source[it] < 0 || cut[it] }) return base.channels(type, categories.map { source[it] })
        return categories.flatMap { live[it].items }
    }

    override fun shows(categories: List<Int>): List<Show> = base.shows(categories)

    override fun sorted(items: List<Channel>, mode: SortMode): List<Channel> = base.sorted(items, mode)

    override fun sortedShows(items: List<Show>, mode: SortMode): List<Show> = base.sortedShows(items, mode)

    override fun search(type: ContentType, query: String, limit: Int): List<Channel> {
        if (type != ContentType.LIVE || (hiddenKeys.isEmpty() && hiddenGroups.isEmpty())) return base.search(type, query, limit)
        return base.search(type, query, limit + EXTRA).filter { visible(it) }.take(limit)
    }

    override fun searchShows(query: String, limit: Int): List<Show> = base.searchShows(query, limit)

    override fun find(type: ContentType, url: String): Channel? = base.find(type, url)

    override fun findShow(group: String?, title: String): Show? = base.findShow(group, title)

    override fun favorites(keys: Set<String>): List<Channel> = base.favorites(keys)

    override fun favoriteShows(keys: Set<String>): List<Show> = base.favoriteShows(keys)

    override fun allLive(): List<Channel> = base.allLive().let { all -> if (hiddenKeys.isEmpty() && hiddenGroups.isEmpty()) all else all.filter { visible(it) } }

    /** Nothing: the catalog underneath belongs to its owner. */
    override fun close() = Unit

    private fun visible(channel: Channel): Boolean =
        FavoriteKey.of(channel) !in hiddenKeys && channel.group.orEmpty() !in hiddenGroups

    companion object {
        /** Search results asked beyond the limit, so hidden ones taken out still leave enough. */
        private const val EXTRA = 60

        /** A group bigger than this is left whole when it holds hidden channels (it is not a live group any more). */
        private const val CUT_LIMIT = 20_000

        /** [base] as arranged by [prefs]; [base] itself when there is nothing to arrange. */
        fun of(base: Catalog, prefs: ChannelPrefs): Catalog {
            if (prefs.isEmpty || base.isEmpty) return base
            val hiddenKeys = prefs.hiddenChannels.mapTo(HashSet()) { it.key }
            val hiddenGroups = prefs.hiddenGroups
            val categories = ArrayList<Category>()
            val source = ArrayList<Int>()
            val cut = ArrayList<Boolean>()

            // The viewer's own groups, first. A channel is found in the list by its key; hidden ones do not show.
            val wanted = prefs.groups.flatMapTo(HashSet()) { it.keys }
            val found: Map<String, Channel> = if (wanted.isEmpty()) emptyMap() else
                base.favorites(wanted).filter { it.type == ContentType.LIVE }.associateBy { FavoriteKey.of(it) }
            for (group in prefs.groups) {
                val items = group.keys.mapNotNull { found[it] }.filter { FavoriteKey.of(it) !in hiddenKeys }
                if (items.isEmpty()) continue
                categories += Category(ChannelPrefs.shownName(group.name), items)
                source += -1
                cut += true
            }
            val hiddenIn = prefs.hiddenChannels.groupingBy { it.group }.eachCount()
            base.live.forEachIndexed { index, category ->
                if (category.name in hiddenGroups) return@forEachIndexed
                val touched = hiddenIn[category.name] != null && category.items.size <= CUT_LIMIT
                categories += if (touched) Category(category.name, category.items.filter { FavoriteKey.of(it) !in hiddenKeys }) else category
                source += index
                cut += touched
            }
            return CustomizedCatalog(base, hiddenKeys, hiddenGroups, categories, source.toIntArray(), cut.toBooleanArray())
        }
    }
}
