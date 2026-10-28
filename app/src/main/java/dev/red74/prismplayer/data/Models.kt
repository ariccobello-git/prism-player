package dev.red74.prismplayer.data

import kotlinx.serialization.Serializable

/** Whether the playlist has been checked on this device: never, successfully, or unsuccessfully (last check). */
@Serializable
enum class PlaylistStatus { UNVERIFIED, OK, FAILED }

/** A playlist the user saved. The [url] is a secret: it is only ever stored encrypted and never shown in full. */
@Serializable
data class SavedPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val addedAtEpochMs: Long,
    val status: PlaylistStatus = PlaylistStatus.UNVERIFIED,
    val channelCount: Int = 0,
    val checkedAtEpochMs: Long = 0L,
)

/** One playable entry parsed from a playlist. Held in memory only, never persisted. */
data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
)
