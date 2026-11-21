package dev.red74.prismplayer.player

/**
 * Which channel of the list was on before the current one, for the "last channel" key (like a TV remote's "back"):
 * pressing it again goes back to where it started, so two channels can be watched in turn.
 */
class ZapHistory {
    private var last = NONE

    /** Called whenever the channel changes. A "change" to the same channel leaves the history as it was. */
    fun moved(from: Int, to: Int) {
        if (from != to) last = from
    }

    /** The channel to go back to, or null when there is none (nothing before, or the list changed under it). */
    fun target(current: Int, size: Int): Int? = last.takeIf { it in 0 until size && it != current }

    fun clear() {
        last = NONE
    }

    private companion object {
        const val NONE = -1
    }
}

/**
 * The channels next to the one on screen in a list that wraps around (what Up and Down move to). Only these are
 * prepared ahead of time (their guide text and logos from other servers): a stream is never opened for them, because
 * the provider allows one connection per list.
 */
object Neighbours {
    fun around(index: Int, size: Int): List<Int> {
        if (size < 2 || index !in 0 until size) return emptyList()
        return listOf((index + 1) % size, (index - 1 + size) % size).distinct().filter { it != index }
    }
}
