package dev.red74.prismplayer.data

import android.net.Uri

/**
 * What goes on the "Play next" row of the Android TV home screen: the films and series left half way. A card carries
 * only the list's id and a hashed key ([FavoriteKey]) in its link, never a stream address (which holds credentials).
 */
object WatchNext {
    const val SCHEME = "prismplayer"
    const val HOST = "watch"
    const val LIMIT = 5

    /** One card: a film, or a series (shown by its latest episode). [key] is the film's key or the show's key. */
    class Entry(
        val key: String,
        val title: String,
        val note: String?,
        val logo: String?,
        val series: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val savedAt: Long,
    )

    /** Where a card leads: [series] tells which kind [key] names. */
    class Link(val listId: String, val key: String, val series: Boolean)

    fun link(listId: String, key: String, series: Boolean): String =
        Uri.Builder().scheme(SCHEME).authority(HOST)
            .appendQueryParameter("list", listId).appendQueryParameter("key", key)
            .appendQueryParameter("kind", if (series) "series" else "film").build().toString()

    /** The link inside [uri], or null when it is not one of ours or is malformed. */
    fun parse(uri: Uri?): Link? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val list = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
        val key = uri.getQueryParameter("key")?.takeIf { KEY.matches(it) } ?: return null
        val series = when (uri.getQueryParameter("kind")) {
            "series" -> true
            "film" -> false
            else -> return null
        }
        return Link(list, key, series)
    }

    private val KEY = Regex("[0-9a-f]{16,64}")

    /** A picture address is used only when it is on another server than the stream (the stream's address is private). */
    fun safeLogo(logo: String?, streamUrl: String): String? {
        val value = logo?.trim().orEmpty()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        val host = runCatching { Uri.parse(value) }.getOrNull()
        if (host == null || host.userInfo != null || host.host.isNullOrEmpty()) return null
        if (host.host.equals(runCatching { Uri.parse(streamUrl).host }.getOrNull(), ignoreCase = true)) return null
        return value
    }

    /**
     * The films and series of [catalog] with a saved point, the most recently watched first, at most [LIMIT]. A series
     * is one card, for its latest episode.
     */
    fun entries(catalog: Catalog, keys: Set<String> = WatchProgress.keys(), pointOf: (String) -> WatchProgress.Point? = WatchProgress::get): List<Entry> {
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        for (film in catalog.favorites(keys)) {
            if (film.type != ContentType.MOVIE || film.url.isEmpty()) continue
            val point = pointOf(film.url) ?: continue
            out += Entry(FavoriteKey.of(film), VariantTags.of(film.name).title, film.group, safeLogo(film.logo, film.url), false, point.positionMs, point.durationMs, point.savedAt)
        }
        val latest = LinkedHashMap<Pair<String?, String>, Pair<Channel, WatchProgress.Point>>()
        catalog.episodesWithKeys(keys).filter { it.url.isNotEmpty() && it.seriesTitle != null }
            .mapNotNull { e -> pointOf(e.url)?.let { e to it } }
            .sortedByDescending { it.second.savedAt }
            .forEach { latest.putIfAbsent(it.first.group to it.first.seriesTitle!!, it) }
        for ((id, pair) in latest) {
            val show = catalog.findShow(id.first, id.second) ?: continue
            val (episode, point) = pair
            out += Entry(
                FavoriteKey.ofShow(show), VariantTags.of(show.title).title, "S${episode.season} E${episode.episode}",
                safeLogo(show.logo, episode.url), true, point.positionMs, point.durationMs, point.savedAt,
            )
        }
        return out.sortedByDescending { it.savedAt }.take(LIMIT)
    }
}
