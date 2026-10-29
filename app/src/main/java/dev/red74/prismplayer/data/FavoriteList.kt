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
        /** Live channels, then films, then series, in list order (the list database finds them by key). */
        suspend fun find(catalog: Catalog, keys: Set<String>): FavoriteList {
            if (keys.isEmpty()) return FavoriteList(emptyList())
            val channels = catalog.favorites(keys)
            currentCoroutineContext().ensureActive()
            val shows = catalog.favoriteShows(keys)
            return FavoriteList(
                channels.map { FavoriteEntry(FavoriteKey.of(it), it, null) } +
                    shows.map { FavoriteEntry(FavoriteKey.ofShow(it), null, it) },
            )
        }
    }
}
