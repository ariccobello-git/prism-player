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
        // Identities commonly accepted by IPTV panels; some panels answer 404 to anything they do not know.
        "okhttp/4.12.0",
        "IPTVSmartersPro",
        "Lavf/60.3.100",
        "Kodi/20.2 (Linux; Android 12) App_Bitness/32 Version/20.2-Git:20230801-nogitfound",
        "TiviMate/5.0.1",
        "Dalvik/2.1.0 (Linux; U; Android 12; TV Build/STT1.220510.001)",
        "", // no custom identity at all: the platform default
    )
}
