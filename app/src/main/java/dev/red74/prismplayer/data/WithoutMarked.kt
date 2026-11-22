package dev.red74.prismplayer.data

/**
 * Films and episodes marked unavailable (see [Availability]) are left out of the on-demand lists. A group can hold
 * tens of thousands of films read page by page from the database, so the result is a view of the same list that
 * skips the marked positions: nothing is copied and pages are still read only when shown.
 */
object WithoutMarked {
    fun <T> of(items: List<T>, marked: Set<String>, keyOf: (T) -> String): List<T> {
        if (marked.isEmpty() || items.isEmpty()) return items
        val keep = IntArray(items.size)
        var count = 0
        for (i in items.indices) if (keyOf(items[i]) !in marked) keep[count++] = i
        return if (count == items.size) items else Picked(items, keep.copyOf(count))
    }

    private class Picked<T>(private val base: List<T>, private val positions: IntArray) : AbstractList<T>(), RandomAccess {
        override val size: Int get() = positions.size
        override fun get(index: Int): T = base[positions[index]]
    }
}
