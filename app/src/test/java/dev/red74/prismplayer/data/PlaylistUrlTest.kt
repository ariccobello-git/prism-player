package dev.red74.prismplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUrlTest {
    private fun reason(raw: String): PlaylistUrl.Reason =
        (PlaylistUrl.validate(raw) as PlaylistUrl.Result.Invalid).reason

    @Test
    fun acceptsHttpAndHttps() {
        val https = PlaylistUrl.validate("  https://example.com/list.m3u?x=1  ")
        assertTrue(https is PlaylistUrl.Result.Valid)
        https as PlaylistUrl.Result.Valid
        assertEquals("https://example.com/list.m3u?x=1", https.url)
        assertEquals("example.com", https.host)

        assertTrue(PlaylistUrl.validate("http://192.168.1.10:8080/playlist.m3u8") is PlaylistUrl.Result.Valid)
    }

    @Test
    fun rejectsOtherSchemesAndMissingScheme() {
        assertEquals(PlaylistUrl.Reason.UNSUPPORTED_SCHEME, reason("ftp://example.com/a.m3u"))
        assertEquals(PlaylistUrl.Reason.UNSUPPORTED_SCHEME, reason("file:///etc/passwd"))
        assertEquals(PlaylistUrl.Reason.UNSUPPORTED_SCHEME, reason("javascript:alert(1)"))
        assertEquals(PlaylistUrl.Reason.UNSUPPORTED_SCHEME, reason("example.com/list.m3u"))
    }

    @Test
    fun rejectsEmptyOverlongAndMalformed() {
        assertEquals(PlaylistUrl.Reason.EMPTY, reason("   "))
        assertEquals(
            PlaylistUrl.Reason.TOO_LONG,
            reason("http://example.com/" + "a".repeat(PlaylistUrl.MAX_LENGTH)),
        )
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason("http://exa mple.com/a"))
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason("http://"))
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason("http:///path"))
    }

    @Test
    fun displayLabelShowsTheHostOnly() {
        val label = PlaylistUrl.displayLabel("https://user:pass@media.example.com:8443/secret/path?token=abc")
        assertEquals("media.example.com", label)
        assertEquals("—", PlaylistUrl.displayLabel("not a url"))
    }

    @Test
    fun withDefaultSchemeAddsHttpOnlyWhenMissing() {
        assertEquals("http://example.com:8080/a.m3u", PlaylistUrl.withDefaultScheme("  example.com:8080/a.m3u "))
        assertEquals("https://example.com/a.m3u", PlaylistUrl.withDefaultScheme("https://example.com/a.m3u"))
        assertEquals("", PlaylistUrl.withDefaultScheme("   "))
        assertEquals("ftp://example.com/a", PlaylistUrl.withDefaultScheme("ftp://example.com/a"))
    }
}
