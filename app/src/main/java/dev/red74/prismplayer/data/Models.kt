package dev.red74.prismplayer.data

import kotlinx.serialization.Serializable

/** A playlist the user saved. The [url] is a secret: it is only ever stored encrypted and never shown in full. */
@Serializable
data class SavedPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val addedAtEpochMs: Long,
)

/** One playable entry parsed from a playlist. Held in memory only, never persisted. */
data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
)
