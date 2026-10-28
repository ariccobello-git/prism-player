package dev.red74.prismplayer.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun encodesAPairingUrlIntoASquareGridWithFinderPatterns() {
        val matrix = QrCode.encode("http://192.168.1.20:41234/pair?c=K7QF3MWD")

        assertTrue("QR version 1 is 21x21 at minimum", matrix.size >= 21)
        // Top-left finder pattern: solid 7x7 ring, light separator at index 7.
        for (i in 0..6) {
            assertTrue(matrix[i, 0])
            assertTrue(matrix[0, i])
            assertTrue(matrix[6, i])
            assertTrue(matrix[i, 6])
        }
        assertFalse(matrix[7, 0])
        assertFalse(matrix[0, 7])
        // Same finder pattern at the top-right and bottom-left corners.
        val last = matrix.size - 1
        assertTrue(matrix[last, 0])
        assertTrue(matrix[0, last])
    }

    @Test
    fun differentContentGivesDifferentGrids() {
        val a = QrCode.encode("http://10.0.0.2:1000/pair?c=AAAAAAAA")
        val b = QrCode.encode("http://10.0.0.2:1000/pair?c=BBBBBBBB")
        assertEquals(a.size, b.size)
        var differences = 0
        for (y in 0 until a.size) for (x in 0 until a.size) if (a[x, y] != b[x, y]) differences++
        assertTrue(differences > 0)
    }
}
