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
    /** Name of the last load failure ([dev.red74.prismplayer.LoadError]) or empty; never contains the URL. */
    val lastError: String = "",
    val lastHttpCode: Int = 0,
)

/** What an entry is, as far as the list says: a TV channel, a film or an episode of a series. */
enum class ContentType { LIVE, MOVIE, SERIES }

/**
 * One playable entry parsed from a playlist. Held in memory only, never persisted.
 * [logo] is the poster or channel logo, [tvgId] links a channel to the programme guide,
 * and for series the entry carries its show title, season and episode.
 */
data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val type: ContentType = ContentType.LIVE,
    val seriesTitle: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
)
