package dev.red74.prismplayer.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The provider allows one connection per account: panel requests never overlap a stream or each other. */
class PanelLineTest {
    @Test
    fun aRequestWaitsWhileAStreamIsOpen() = runBlocking(Dispatchers.Default) {
        PanelLine.beginStream()
        val job = async { PanelLine.request(null) { "done" } }
        delay(100)
        assertFalse(job.isCompleted)
        PanelLine.endStream()
        assertEquals("done", withTimeout(2_000) { job.await() })
    }

    @Test
    fun aStreamCutsARunningRequestWhichStartsOverAfterwards() = runBlocking(Dispatchers.Default) {
        var runs = 0
        val started = CompletableDeferred<Unit>()
        val job = async {
            PanelLine.request(null) {
                runs++
                started.complete(Unit)
                if (runs == 1) {
                    while (!PanelLine.cut) delay(5)
                    throw IOException("connection closed")
                }
                "ok"
            }
        }
        started.await()
        // Returns only once the running request has let go of its connection.
        PanelLine.beginStream()
        delay(50)
        assertFalse(job.isCompleted)
        PanelLine.endStream()
        assertEquals("ok", withTimeout(2_000) { job.await() })
        assertEquals(2, runs)
    }

    @Test
    fun requestsRunOneAtATime() = runBlocking(Dispatchers.Default) {
        var inside = 0
        var most = 0
        val jobs = List(8) {
            async {
                PanelLine.request(null) {
                    inside++
                    most = maxOf(most, inside)
                    delay(10)
                    inside--
                }
            }
        }
        jobs.forEach { it.await() }
        assertEquals(1, most)
    }

    @Test
    fun aRealFailureIsReportedNotRetried() = runBlocking(Dispatchers.Default) {
        var runs = 0
        val result = runCatching { PanelLine.request(null) { runs++; throw IOException("refused") } }
        assertEquals("refused", result.exceptionOrNull()?.message)
        assertEquals(1, runs)
    }

    @Test
    fun picturesOnTheProviderHostAreRecognised() {
        ProviderHosts.noteUrl("http://Panel.Example:8080/get.php?username=demo&password=demo")
        assertEquals(true, "panel.example" in ProviderHosts)
        assertEquals(true, "PANEL.example" in ProviderHosts)
        assertFalse("images.example" in ProviderHosts)
    }
}
