package dev.red74.prismplayer.player

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new stream waits until the old one's connection is really closed: never two connections of a list at once. */
class StreamConnectionsTest {
    @Test
    fun aNewStreamWaitsWhileAConnectionIsOpen() = runBlocking {
        StreamConnections.opening()
        assertEquals(1, StreamConnections.count)
        assertFalse(StreamConnections.awaitNone(50))
        StreamConnections.closed()
        assertEquals(0, StreamConnections.count)
        assertTrue(StreamConnections.awaitNone(50))
    }

    @Test
    fun itStartsAsSoonAsTheOldConnectionCloses() = runBlocking {
        StreamConnections.opening()
        val waiting = async { StreamConnections.awaitNone(2_000) }
        delay(100)
        assertFalse(waiting.isCompleted)
        StreamConnections.closed()
        assertTrue(waiting.await())
    }

    @Test
    fun theCountNeverGoesBelowZero() {
        StreamConnections.closed()
        StreamConnections.closed()
        assertEquals(0, StreamConnections.count)
        StreamConnections.opening()
        assertEquals(1, StreamConnections.count)
        StreamConnections.closed()
        assertEquals(0, StreamConnections.count)
    }
}
