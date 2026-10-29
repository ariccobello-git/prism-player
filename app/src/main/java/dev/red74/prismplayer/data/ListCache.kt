package dev.red74.prismplayer.data

import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the last downloaded copy of each playlist on the device so reopening it is instant (like the "cached
 * playlist" of other IPTV apps) and the network is only used to refresh it now and then.
 *
 * The stream URLs inside a playlist carry the account credentials, so the copy is compressed and then encrypted
 * in 64 KB sealed blocks (AES-256-GCM, key from the Android Keystore) and stored in the app's private,
 * not-backed-up folder. A damaged or altered file is detected and simply ignored.
 */
class ListCache(private val dir: File, private val keyProvider: () -> SecretKey) {

    private fun fileFor(id: String): File = File(dir, "list-" + id.filter { it.isLetterOrDigit() || it == '-' } + ".bin")

    fun ageMs(id: String, now: Long = System.currentTimeMillis()): Long? =
        fileFor(id).takeIf { it.isFile && it.length() > 0 }?.let { now - it.lastModified() }

    /** Plain bytes of the cached list, or null if there is none or it cannot be read. The caller closes the stream. */
    fun open(id: String): InputStream? {
        val file = fileFor(id)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            GZIPInputStream(BlockDecryptStream(file.inputStream().buffered(IO_BUFFER), keyProvider), IO_BUFFER)
        } catch (e: Exception) {
            null
        }
    }

    /** Starts writing a new copy; it replaces the old one only on [Writer.commit]. */
    fun writer(id: String): Writer {
        dir.mkdirs()
        return Writer(fileFor(id), File(dir, "tmp-" + System.nanoTime() + ".part"), keyProvider)
    }

    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
    }

    class Writer internal constructor(
        private val target: File,
        private val temp: File,
        keyProvider: () -> SecretKey,
    ) : OutputStream() {
        private val gzip: GZIPOutputStream
        private var finished = false

        init {
            val sealed = BlockEncryptStream(temp.outputStream().buffered(IO_BUFFER), keyProvider)
            gzip = object : GZIPOutputStream(sealed, IO_BUFFER) {
                init { def.setLevel(Deflater.BEST_SPEED) }
            }
        }

        override fun write(b: Int) = gzip.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = gzip.write(b, off, len)

        /** Finishes the file and puts it in place. Returns false if anything went wrong (the old copy stays). */
        fun commit(): Boolean {
            if (finished) return false
            finished = true
            return try {
                gzip.close()
                target.delete()
                temp.renameTo(target)
            } catch (e: Exception) {
                temp.delete()
                false
            }
        }

        fun abort() {
            if (finished) return
            finished = true
            runCatching { gzip.close() }
            temp.delete()
        }

        override fun close() = abort()
    }

    /** Copies what the parser reads into the cache writer; a cache failure never disturbs the parse. */
    class Tee(source: InputStream, private val sink: OutputStream) : FilterInputStream(source) {
        private var broken = false
        private fun copy(b: ByteArray, off: Int, n: Int) {
            if (n <= 0 || broken) return
            try { sink.write(b, off, n) } catch (e: IOException) { broken = true }
        }

        override fun read(): Int {
            val v = super.read()
            if (v >= 0 && !broken) {
                try { sink.write(v) } catch (e: IOException) { broken = true }
            }
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            copy(b, off, n)
            return n
        }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported(): Boolean = false
    }

    private class BlockEncryptStream(out: OutputStream, private val keyProvider: () -> SecretKey) : FilterOutputStream(out) {
        private val block = ByteArray(BLOCK)
        private var filled = 0

        override fun write(b: Int) {
            block[filled++] = b.toByte()
            if (filled == BLOCK) flushBlock()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            var left = len
            while (left > 0) {
                val n = minOf(left, BLOCK - filled)
                System.arraycopy(b, pos, block, filled, n)
                filled += n; pos += n; left -= n
                if (filled == BLOCK) flushBlock()
            }
        }

        private fun flushBlock() {
            if (filled == 0) return
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            val iv = cipher.iv
            val sealed = cipher.doFinal(block, 0, filled)
            val total = iv.size + sealed.size
            out.write(byteArrayOf((total ushr 24).toByte(), (total ushr 16).toByte(), (total ushr 8).toByte(), total.toByte(), iv.size.toByte()))
            out.write(iv)
            out.write(sealed)
            filled = 0
        }

        override fun flush() = Unit

        override fun close() {
            flushBlock()
            out.flush()
            out.close()
        }
    }

    private class BlockDecryptStream(input: InputStream, private val keyProvider: () -> SecretKey) : InputStream() {
        private val source = input
        private var current = ByteArray(0)
        private var pos = 0
        private var end = false

        private fun fill(): Boolean {
            if (end) return false
            val header = ByteArray(5)
            var got = 0
            while (got < 5) {
                val r = source.read(header, got, 5 - got)
                if (r < 0) {
                    if (got == 0) { end = true; return false }
                    throw IOException("truncated block")
                }
                got += r
            }
            val total = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val ivLength = header[4].toInt() and 0xFF
            if (ivLength < 12 || total < ivLength + TAG_BYTES || total > BLOCK + 64) throw IOException("bad block")
            val body = ByteArray(total)
            var read = 0
            while (read < total) {
                val r = source.read(body, read, total - read)
                if (r < 0) throw IOException("truncated block")
                read += r
            }
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, body, 0, ivLength))
                current = cipher.doFinal(body, ivLength, total - ivLength)
            } catch (e: GeneralSecurityException) {
                throw IOException("block not authentic")
            }
            pos = 0
            return true
        }

        override fun read(): Int {
            if (pos >= current.size && !fill()) return -1
            return current[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= current.size && !fill()) return -1
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = source.close()
    }

    private companion object {
        const val BLOCK = 64 * 1024
        const val IO_BUFFER = 64 * 1024
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
