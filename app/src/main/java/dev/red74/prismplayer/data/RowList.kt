package dev.red74.prismplayer.data

/**
 * The row numbers behind a list on screen. A category of the list database is one run of consecutive rows, the
 * "all" entry of a group is several runs one after the other, and a sorted view is an explicit array.
 */
interface RowIds {
    val size: Int
    fun at(index: Int): Long

    /** Consecutive runs of rows: [starts] and [counts] have the same length. */
    class Runs(private val starts: LongArray, private val counts: IntArray) : RowIds {
        private val offsets = IntArray(counts.size + 1).also { o ->
            for (i in counts.indices) o[i + 1] = o[i] + counts[i]
        }
        override val size: Int = offsets.last()

        /** The runs as (first row, last row) pairs, for a database query. */
        val ranges: List<Pair<Long, Long>> get() = starts.indices.filter { counts[it] > 0 }.map { starts[it] to starts[it] + counts[it] - 1 }

        override fun at(index: Int): Long {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
            var lo = 0
            var hi = counts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (offsets[mid] <= index) lo = mid else hi = mid - 1
            }
            return starts[lo] + (index - offsets[lo])
        }
    }

    class Explicit(private val ids: LongArray) : RowIds {
        override val size: Int get() = ids.size
        override fun at(index: Int): Long = ids[index]
    }

    class Reversed(private val source: RowIds) : RowIds {
        override val size: Int get() = source.size
        override fun at(index: Int): Long = source.at(size - 1 - index)
    }
}

/**
 * A list whose entries are read from storage a page at a time, only when the screen asks for them, so a group of
 * tens of thousands of entries costs only the rows actually shown. [fetch] receives the row numbers of one page
 * and returns the entries in the same order. A few recent pages are kept.
 *
 * Equality is identity: comparing two such lists element by element would read every row.
 */
class RowList<T>(
    val ids: RowIds,
    private val pageSize: Int = 64,
    private val keepPages: Int = 8,
    private val fetch: (LongArray) -> List<T>,
) : AbstractList<T>(), RandomAccess {
    private val pages = object : LinkedHashMap<Int, List<T>>(keepPages * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<T>>?): Boolean = size > keepPages
    }

    override val size: Int get() = ids.size

    override fun get(index: Int): T {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index of $size")
        val page = index / pageSize
        val rows = synchronized(pages) { pages[page] } ?: load(page)
        return rows[index - page * pageSize]
    }

    private fun load(page: Int): List<T> {
        val from = page * pageSize
        val to = minOf(size, from + pageSize)
        val wanted = LongArray(to - from) { ids.at(from + it) }
        val rows = fetch(wanted)
        check(rows.size == wanted.size) { "page has ${rows.size} rows instead of ${wanted.size}" }
        synchronized(pages) { pages[page] = rows }
        return rows
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
