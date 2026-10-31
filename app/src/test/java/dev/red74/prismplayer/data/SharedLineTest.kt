package dev.red74.prismplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Invented panel and credentials only. */
class SharedLineTest {
    private val link = IprismLink("https://iprism.example.workers.dev/api/tv-line", "abc.access", "s3cret")
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        SharedLine.resetForTest()
        SharedLine.setLink(link)
        calls.clear()
    }

    @After
    fun tearDown() {
        SharedLine.resetForTest()
        SharedLine.setLink(null)
        SharedLine.transport = { _, _, _, _ -> null }
    }

    @Test
    fun everyAddressOfAnAccountGivesTheSameKeyAsIprism() {
        // Same vectors as iPrism's scripts/line-test.mjs.
        val k1 = "af9d2467cb077a8cf021609903601fa7"
        assertEquals(k1, LineKey.of("http://panel.example:8080/get.php?username=demo&password=secret&type=m3u_plus"))
        assertEquals(k1, LineKey.of("http://panel.example:8080/live/demo/secret/123.ts"))
        assertEquals(k1, LineKey.of("http://other.example/movie/demo/secret/9.mkv"))
        assertEquals(k1, LineKey.of("http://panel.example:8080/demo/secret/123"))
        assertEquals(k1, LineKey.of("http://panel.example:8080/player_api.php?username=demo&password=secret&action=get_series"))
        assertNotEquals(k1, LineKey.of("http://panel.example:8080/get.php?username=demo2&password=secret"))
        assertEquals("ad1906e532849b8b9faafd41c9a957e5", LineKey.of("https://lists.example/y.m3u"))
        assertEquals(LineKey.of("https://Lists.Example/x.m3u"), LineKey.of("https://lists.example/y.m3u"))
        assertNull(LineKey.account("https://lists.example/y.m3u"))
        assertNull(LineKey.of("not a url"))
    }

    @Test
    fun theCodeFromIprismIsRecognised() {
        val parsed = IprismLink.parse("  https://iprism.example.workers.dev/api/tv-line#abc.access|s3cret ")
        assertEquals(link, parsed)
        assertEquals(link, IprismLink.parse(link.encode()))
        assertNull(IprismLink.parse("http://iprism.example.workers.dev/api/tv-line#abc|def"))
        assertNull(IprismLink.parse("http://panel.example/get.php?username=a&password=b"))
        // What a phone may do to it on the way: line breaks, encoded '#' and '|'.
        assertEquals(link, IprismLink.parse("https://iprism.example.workers.dev/api/tv-line\n#abc.access|s3cret"))
        assertEquals(link, IprismLink.parse("https://iprism.example.workers.dev/api/tv-line%23abc.access%7Cs3cret"))
        // Cut short: not a link, but recognised as iPrism's address so it is never saved as a list.
        assertNull(IprismLink.parse("https://iprism.example.workers.dev/api/tv-line"))
        assertTrue(IprismLink.looksLikeOne("https://iprism.example.workers.dev/api/tv-line"))
        assertEquals("#: no, |: no, caratteri dopo #: 0", IprismLink.describe("https://iprism.example.workers.dev/api/tv-line"))
        assertTrue(!IprismLink.looksLikeOne("http://panel.example/get.php?username=a&password=b"))
    }

    @Test
    fun withoutALinkNothingIsAsked() = runBlocking {
        SharedLine.setLink(null)
        SharedLine.transport = { _, op, _, _ -> calls += op; SharedLine.Answer(true, ttlMs = 40_000) }
        assertFalse(SharedLine.take("k1"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aTurnIsWaitedForThenSharedAndFreedAfterTheLastUse() = runBlocking(Dispatchers.Default) {
        var refusals = 1
        SharedLine.transport = { _, op, _, _ ->
            synchronized(calls) { calls += op }
            when {
                op == "take" && refusals-- > 0 -> SharedLine.Answer(false, waitMs = 600, by = "iphone")
                else -> SharedLine.Answer(true, ttlMs = 40_000)
            }
        }
        assertTrue(withTimeout(5_000) { SharedLine.take("k1") })
        assertEquals(listOf("take", "take"), synchronized(calls) { calls.toList() })
        assertNull(SharedLine.note.value)
        // A second use of the same list joins the turn without asking again.
        assertTrue(SharedLine.take("k1"))
        assertEquals(2, synchronized(calls) { calls.size })
        SharedLine.release("k1")
        SharedLine.release("k1")
        delay(1_000)
        assertFalse("free" in synchronized(calls) { calls.toList() })
        delay(3_000)
        assertEquals("free", synchronized(calls) { calls.last() })
    }

    @Test
    fun anUnreachableServerMeansWaitingNotConnecting() = runBlocking(Dispatchers.Default) {
        SharedLine.transport = { _, _, _, _ -> null }
        val job = async { SharedLine.take("k1") }
        delay(500)
        assertFalse(job.isCompleted)
        assertTrue(SharedLine.note.value != null)
        SharedLine.transport = { _, _, _, _ -> SharedLine.Answer(true, ttlMs = 40_000) }
        assertTrue(withTimeout(6_000) { job.await() })
    }
}
