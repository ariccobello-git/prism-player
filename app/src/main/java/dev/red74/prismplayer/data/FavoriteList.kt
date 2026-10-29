package dev.red74.prismplayer.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One favourite found in the catalog: a channel or film, or a series. */
data class FavoriteEntry(val key: String, val channel: Channel?, val show: Show?)

/**
 * The favourites of the open list, found once in the background (when the list is ready or a favourite changes)
 * and kept ready, so the Favourites page shows them at once instead of searching the whole list on every visit.
 */
class FavoriteList(val entries: List<FavoriteEntry>) {
    fun without(key: String): FavoriteList = FavoriteList(entries.filter { it.key != key })

    companion object {
        /** Live channels, then films, then series, each in list order. Stops early when cancelled. */
        suspend fun find(catalog: Catalog, keys: Set<String>): FavoriteList {
            if (keys.isEmpty()) return FavoriteList(emptyList())
            val found = ArrayList<FavoriteEntry>()
            for (categories in listOf(catalog.live, catalog.movies)) {
                for (category in categories) {
                    currentCoroutineContext().ensureActive()
                    for (c in category.items) {
                        val key = FavoriteKey.of(c)
                        if (key in keys) found += FavoriteEntry(key, c, null)
                    }
                }
            }
            for (category in catalog.series) {
                currentCoroutineContext().ensureActive()
                for (s in category.shows) {
                    val key = FavoriteKey.ofShow(s)
                    if (key in keys) found += FavoriteEntry(key, null, s)
                }
            }
            return FavoriteList(found)
        }
    }
}
