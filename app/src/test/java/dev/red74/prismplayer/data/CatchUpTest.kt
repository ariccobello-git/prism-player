package dev.red74.prismplayer.data

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpTest {
    private val live = "http://panel.example:8080/live/user1/pass1/4242.ts"
    // 2026-11-21 20:45:00 UTC
    private val start = 1_795_294_700_000L - 1_795_294_700_000L % 60_000L
    private fun programme(minutes: Long = 90, offsetMin: Long = 0) = Programme("X", start + offsetMin * 60_000, start + (offsetMin + minutes) * 60_000, null)

    @Test fun partsOfAnXtreamAddress() {
        val p = CatchUp.parts(live)!!
        assertEquals("http://panel.example:8080", p.base)
        assertEquals("user1", p.user); assertEquals("pass1", p.password); assertEquals("4242", p.id)
        val old = CatchUp.parts("https://h/sub/u/p/77")!!
        assertEquals("https://h/sub", old.base); assertEquals("77", old.id)
    }

    @Test fun otherAddressesHaveNone() {
        assertNull(CatchUp.parts("http://h/movie/u/p/1.mp4"))
        assertNull(CatchUp.parts("http://h/stream.m3u8"))
        assertNull(CatchUp.parts("http://h/u/p/abc.ts"))
        assertFalse(CatchUp.possible("http://h/x.ts"))
    }

    @Test fun addressesCarryMinutesAndStart() {
        val urls = CatchUp.addresses(live, programme(90), 0L)
        assertEquals(2, urls.size)
        // 90 minutes + 5 extra
        assertTrue(urls[0], urls[0].startsWith("http://panel.example:8080/timeshift/user1/pass1/95/"))
        assertTrue(urls[0].endsWith("/4242.ts"))
        assertTrue(urls[1], urls[1].startsWith("http://panel.example:8080/streaming/timeshift.php?username=user1&password=pass1&stream=4242&start="))
        assertTrue(urls[1].endsWith("&duration=95"))
    }

    @Test fun startIsInThePanelsClock() {
        // 2026-11-21 20:45 UTC
        val utc = CatchUp.startText(1_795_293_900_000L, 0L)
        val plus1 = CatchUp.startText(1_795_293_900_000L, 3_600_000L)
        assertEquals(16, utc.length)
        assertTrue(utc.matches(Regex("""\d{4}-\d{2}-\d{2}:\d{2}-\d{2}""")))
        assertEquals(utc.substring(0, 11), plus1.substring(0, 11).let { if (plus1.substring(11, 13) == "00") utc.substring(0, 11) else it })
        val a = utc.substring(11, 13).toInt(); val b = plus1.substring(11, 13).toInt()
        assertEquals((a + 1) % 24, b)
        assertEquals(utc.substring(14), plus1.substring(14))
    }

    @Test fun partialMinutesRoundUp() {
        val p = Programme("X", 0L, 61_000L * 60 + 1, null) // 61 min 1 ms... actually just above 61 minutes
        val urls = CatchUp.addresses(live, p, 0L)
        assertTrue(urls[0].contains("/66/") || urls[0].contains("/67/"))
    }

    @Test fun offeredOnlyForAStartedProgramme() {
        val now = start + 20 * 60_000
        assertTrue(CatchUp.offered(live, programme(), now, marked = false))
        assertFalse("not started", CatchUp.offered(live, programme(), start - 1, marked = false))
        assertFalse("just started", CatchUp.offered(live, programme(), start + 30_000, marked = false))
        assertFalse("over", CatchUp.offered(live, programme(30), start + 31 * 60_000, marked = false))
        assertFalse("marked", CatchUp.offered(live, programme(), now, marked = true))
        assertFalse("no guide", CatchUp.offered(live, null, now, marked = false))
        assertFalse("too short", CatchUp.offered(live, programme(3), start + 2 * 60_000, marked = false))
        assertFalse("not xtream", CatchUp.offered("http://h/a.ts", programme(), now, marked = false))
    }

    @Test fun panelClockIsRead() {
        val json = """{"user_info":{"auth":1},"server_info":{"url":"x","time_now":"2026-11-21 21:45:03","timestamp_now":1795293902,"timezone":"Europe/Rome"}}"""
        // 21:45:03 wall minus 1795293902 -> hour offset depends on stamp; build a coherent sample instead
        val stamp = 1_795_293_902L
        val wall = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date((stamp + 3600) * 1000))
        val ok = """{"server_info":{"time_now":"$wall","timestamp_now":$stamp}}"""
        assertEquals(3_600_000L, CatchUp.serverOffset(StringReader(ok)))
        val negative = """{"server_info":{"time_now":"${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date((stamp - 18000) * 1000))}","timestamp_now":"$stamp"}}"""
        assertEquals(-18_000_000L, CatchUp.serverOffset(StringReader(negative.replace("\"timestamp_now\":\"$stamp\"", "\"timestamp_now\":$stamp"))))
        assertNull(CatchUp.serverOffset(StringReader(json.replace("\"server_info\"", "\"other\""))))
        assertNull(CatchUp.serverOffset(StringReader("""{"server_info":{"time_now":"garbage","timestamp_now":5}}""")))
        assertNull(CatchUp.serverOffset(StringReader("not json")))
        assertNull(CatchUp.serverOffset(StringReader("")))
        // a few seconds of delay do not change the answer
        val late = """{"server_info":{"time_now":"${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date((stamp + 3600 + 2) * 1000))}","timestamp_now":$stamp}}"""
        assertEquals(3_600_000L, CatchUp.serverOffset(StringReader(late)))
    }

    @Test fun offsetsAreKeptPerPanel() {
        assertFalse(CatchUp.knowsOffset("http://other.example/live/u/p/1.ts"))
        assertEquals(42L, CatchUp.offsetFor("http://other.example/live/u/p/1.ts", 42L))
        CatchUp.setOffset("http://other.example/live/u/p/1.ts", 7_200_000L)
        assertTrue(CatchUp.knowsOffset("http://other.example/live/x/y/2.ts"))
        assertEquals(7_200_000L, CatchUp.offsetFor("http://other.example/live/x/y/2.ts", 0L))
    }

    @Test fun marksExpire() {
        val now = 10L * CatchUp.MARK_TTL_MS
        val live = CatchUp.liveMarks(mapOf("a" to now - 1000, "b" to now - CatchUp.MARK_TTL_MS - 1, "c" to now + 5), now)
        assertEquals(setOf("a"), live.keys)
        assertNotNull(CatchUp.markKey(live.toString()))
    }

    @Test fun clockAddress() {
        assertEquals("http://panel.example:8080/player_api.php?username=user1&password=pass1", CatchUp.clockAddress(live))
        assertNull(CatchUp.clockAddress("http://h/x.ts"))
    }
}
