package dev.red74.prismplayer.data

/**
 * IPTV panels sometimes refuse a particular User-Agent. A list is therefore requested with a plain app identity
 * first (the same kind of identity an older, working player used) and, if the panel refuses it or answers with
 * something that is not a playlist, with the other common ones in turn.
 */
object HttpAgent {
    val LIST_AGENTS = listOf(
        "Prism/1 (Android TV)",
        "VLC/3.0.20 LibVLC/3.0.20",
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
    )
}
