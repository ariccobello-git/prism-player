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
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason("http://"))
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason("http:///path"))
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason(PlaylistUrl.withDefaultScheme("Q47H-8VBC")))
        assertTrue(PlaylistUrl.validate("http://[::1]:8080/a.m3u") is PlaylistUrl.Result.Valid)
    }

    @Test
    fun pastedLinksWithLineBreaksAndSpacesAreCleaned() {
        // A link copied from a chat often has a break after the port.
        val v = PlaylistUrl.validate("http://panel.example:23000\n/get.php?username=demo&password=demo-pass&type=m3u_plus&output=mpegts ")
        assertTrue(v is PlaylistUrl.Result.Valid)
        v as PlaylistUrl.Result.Valid
        assertEquals("http://panel.example:23000/get.php?username=demo&password=demo-pass&type=m3u_plus&output=mpegts", v.url)
        assertEquals("panel.example", v.host)
        assertTrue(PlaylistUrl.validate("http://exa mple.com/a") is PlaylistUrl.Result.Valid)
    }

    @Test
    fun oddButRealWorldCharactersAreAccepted() {
        assertTrue(PlaylistUrl.validate("http://my_panel.example:8080/get.php?u=a|b&p=x{y}") is PlaylistUrl.Result.Valid)
        assertEquals("my_panel.example", (PlaylistUrl.validate("http://my_panel.example/a") as PlaylistUrl.Result.Valid).host)
        assertEquals(PlaylistUrl.Reason.BAD_FORMAT, reason("http://"))
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

    @Test
    fun getPhpLinksHaveOtherSpellings() {
        val url = "http://panel.example:8080/get.php?username=u&password=p&type=m3u_plus&output=mpegts"
        val alts = PlaylistUrl.alternatives(url)
        org.junit.Assert.assertTrue(alts.contains("http://panel.example:8080/get.php?username=u&password=p&type=m3u_plus"))
        org.junit.Assert.assertTrue(alts.contains("http://panel.example:8080/get.php?username=u&password=p&type=m3u_plus&output=ts"))
        org.junit.Assert.assertFalse(alts.contains(url))
        org.junit.Assert.assertTrue(PlaylistUrl.alternatives("http://x.example/list.m3u").isEmpty())
    }
}
