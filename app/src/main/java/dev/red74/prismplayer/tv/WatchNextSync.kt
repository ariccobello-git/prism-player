package dev.red74.prismplayer.tv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.red74.prismplayer.data.WatchNext

/**
 * Keeps the "Play next" row of the Android TV home screen equal to [WatchNext.entries]: new cards are added, changed
 * ones updated, finished ones taken away. A program stores the list's id as its provider id, so a sync of one list
 * never touches the cards of another. Any failure (a TV without the row, a missing permission) is silent.
 */
object WatchNextSync {
    fun sync(context: Context, listId: String, entries: List<WatchNext.Entry>) {
        runCatching {
            val resolver = context.contentResolver
            val existing = HashMap<String, Long>()
            val stale = ArrayList<Long>()
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val program = WatchNextProgram.fromCursor(cursor)
                    if (program.internalProviderId != listId) continue
                    val key = program.contentId
                    if (key == null || entries.none { it.key == key } || existing.containsKey(key)) stale += program.id
                    else existing[key] = program.id
                }
            }
            stale.forEach { resolver.delete(ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, it), null, null) }
            for (entry in entries) {
                val values = program(context, listId, entry).toContentValues()
                val id = existing[entry.key]
                if (id != null) {
                    resolver.update(ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, id), values, null, null)
                } else {
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                }
            }
        }
    }

    private fun program(context: Context, listId: String, entry: WatchNext.Entry): WatchNextProgram {
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(WatchNext.link(listId, entry.key, entry.series)))
            .setPackage(context.packageName)
        val builder = WatchNextProgram.Builder()
            .setType(if (entry.series) TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(entry.savedAt)
            .setTitle(entry.title)
            .setContentId(entry.key)
            .setInternalProviderId(listId)
            .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
        entry.note?.let { builder.setDescription(it) }
        entry.logo?.let {
            builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3)
        }
        if (entry.durationMs > 0) {
            builder.setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return builder.build()
    }
}
