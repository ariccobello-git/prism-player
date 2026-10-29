package dev.red74.prismplayer.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A compact binary picture of an already parsed list. Reading it back is several times faster than reading the
 * playlist text again (no line splitting, no attribute scanning, repeated group names stored once), which is what
 * makes a very large list open quickly on a TV stick. It is stored through [ListCache], so it is encrypted too.
 */
object CatalogSnapshot {
    private const val MAGIC = 0x50534E32 // "PSN2"
    private const val MAX_STRING = 1 shl 20

    fun write(output: OutputStream, result: M3uParser.Result) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        writeString(out, result.epgUrl)
        out.writeBoolean(result.truncated)
        writeVarInt(out, result.channels.size)
        val pool = HashMap<String, Int>()
        for (c in result.channels) {
            writeString(out, c.name)
            writeString(out, c.url)
            writePooled(out, c.group, pool)
            writeString(out, c.logo)
            writeString(out, c.tvgId)
            writeString(out, c.tvgName)
            out.writeByte(c.type.ordinal)
            writePooled(out, c.seriesTitle, pool)
            writeVarInt(out, c.season)
            writeVarInt(out, c.episode)
        }
        out.flush()
    }

    /** Null if the data is not a snapshot of this version or is damaged. */
    fun read(input: InputStream): M3uParser.Result? {
        return try {
            val inp = DataInputStream(input)
            if (inp.readInt() != MAGIC) return null
            val epg = readString(inp)
            val truncated = inp.readBoolean()
            val count = readVarInt(inp)
            if (count < 0 || count > 5_000_000) return null
            val types = ContentType.values()
            val pool = ArrayList<String>()
            val channels = ArrayList<Channel>(count)
            repeat(count) {
                val name = readString(inp) ?: throw IOException("no name")
                val url = readString(inp) ?: throw IOException("no url")
                val group = readPooled(inp, pool)
                val logo = readString(inp)
                val tvgId = readString(inp)
                val tvgName = readString(inp)
                val type = types[inp.readUnsignedByte().also { if (it >= types.size) throw IOException("bad type") }]
                val title = readPooled(inp, pool)
                val season = readVarInt(inp)
                val episode = readVarInt(inp)
                channels += Channel(name, url, group, logo, tvgId, tvgName, type, title, season, episode)
            }
            M3uParser.Result(channels, count, truncated, epg, true)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }

    private fun readVarInt(inp: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = inp.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 28) throw IOException("bad number")
        }
    }

    private fun writeString(out: DataOutputStream, value: String?) {
        if (value == null) {
            writeVarInt(out, 0)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size + 1)
        out.write(bytes)
    }

    private fun readString(inp: DataInputStream): String? {
        val n = readVarInt(inp)
        if (n == 0) return null
        if (n - 1 > MAX_STRING) throw IOException("string too long")
        val bytes = ByteArray(n - 1)
        try {
            inp.readFully(bytes)
        } catch (e: EOFException) {
            throw IOException("truncated")
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 0 = none, 1 = a new string follows, n >= 2 = the (n - 2)-th string already seen. */
    private fun writePooled(out: DataOutputStream, value: String?, pool: HashMap<String, Int>) {
        if (value == null) {
            writeVarInt(out, 0)
            return
        }
        val known = pool[value]
        if (known != null) {
            writeVarInt(out, known + 2)
        } else {
            pool[value] = pool.size
            writeVarInt(out, 1)
            writeString(out, value)
        }
    }

    private fun readPooled(inp: DataInputStream, pool: ArrayList<String>): String? {
        val ref = readVarInt(inp)
        return when {
            ref == 0 -> null
            ref == 1 -> (readString(inp) ?: throw IOException("empty")).also { pool += it }
            else -> pool.getOrNull(ref - 2) ?: throw IOException("bad reference")
        }
    }
}
