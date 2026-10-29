package dev.red74.prismplayer.data

import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ListCacheTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun dir() = File.createTempFile("lcache", "").let { it.delete(); it.mkdirs(); it }

    private fun payload(size: Int): ByteArray {
        val sb = StringBuilder()
        var i = 0
        while (sb.length < size) { sb.append("#EXTINF:-1 group-title=\"G${i % 50}\",Canale $i\nhttp://example.test/live/user/pass/$i.ts\n"); i++ }
        return sb.toString().toByteArray()
    }

    @Test
    fun roundTripAcrossManyBlocks() {
        val cache = ListCache(dir(), { key })
        val data = payload(3_000_000)
        val w = cache.writer("a")
        w.write(data)
        assertTrue(w.commit())
        assertArrayEquals(data, cache.open("a")!!.use { it.readBytes() })
    }

    @Test
    fun fileDoesNotContainReadableText() {
        val d = dir()
        val cache = ListCache(d, { key })
        val w = cache.writer("a")
        w.write(payload(200_000))
        w.commit()
        val raw = d.listFiles()!!.first { it.name.startsWith("list-") }.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("example.test"))
    }

    @Test
    fun tamperedFileIsRejected() {
        val d = dir()
        val cache = ListCache(d, { key })
        val w = cache.writer("a")
        w.write(payload(300_000))
        w.commit()
        val f = d.listFiles()!!.first { it.name.startsWith("list-") }
        val raw = f.readBytes()
        raw[raw.size - 5] = (raw[raw.size - 5].toInt() xor 1).toByte()
        f.writeBytes(raw)
        val stream = cache.open("a")
        if (stream != null) {
            try { stream.use { it.readBytes() }; fail("tampering not detected") } catch (e: IOException) { }
        }
    }

    @Test
    fun oldFormatFileIsIgnored() {
        val d = dir()
        File(d, "list-a.bin").writeBytes(ByteArray(500) { 7 })
        assertNull(ListCache(d, { key }).open("a"))
    }

    @Test
    fun keystoreKeyIsUsedOncePerFileNotPerBlock() {
        var calls = 0
        val cache = ListCache(dir(), { calls++; key })
        val w = cache.writer("a")
        w.write(payload(4_000_000))
        w.commit()
        cache.open("a")!!.use { it.readBytes() }
        assertTrue("key asked $calls times", calls <= 3)
    }

    @Test
    fun snapshotAndTextCopiesDoNotCollide() {
        val d = dir()
        val text = ListCache(d, { key })
        val snap = ListCache(d, { key }, prefix = "snap")
        text.writer("a").also { it.write(byteArrayOf(1, 2, 3)); it.commit() }
        snap.writer("a").also { it.write(byteArrayOf(9, 9)); it.commit() }
        assertArrayEquals(byteArrayOf(1, 2, 3), text.open("a")!!.use { it.readBytes() })
        assertArrayEquals(byteArrayOf(9, 9), snap.open("a")!!.use { it.readBytes() })
    }
}
