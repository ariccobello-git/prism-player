package dev.red74.prismplayer.data

/**
 * One User-Agent for every request the app makes (playlist, guide, streams). IPTV panels often refuse unknown
 * agents and accept the common media-player ones, so this identifies as a generic player.
 */
object HttpAgent {
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
}
