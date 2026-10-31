package dev.red74.prismplayer.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps each opened list as a small database on the device, so a list of hundreds of thousands of entries opens
 * by reading only its group names and counts; the entries themselves are read a page at a time while scrolling,
 * searched with a word index (SQLite FTS4) and sorted by the database.
 *
 * Every category is stored as one run of consecutive rows (and every series as one run of episodes), so showing a
 * group needs no query at all until its rows are drawn.
 *
 * The stream addresses carry the account credentials: each one is sealed with AES-256-GCM using a random key made
 * for this database, and that key is stored sealed by the Android Keystore key. Names, groups
 * and logos are stored as they are. The files live in the app's private, not-backed-up folder.
 */
class CatalogStore(private val dir: File, private val keyProvider: () -> SecretKey) {

    private fun fileFor(id: String): File = File(dir, "db-" + id.filter { it.isLetterOrDigit() || it == '-' } + ".sqlite")

    fun ageMs(id: String, now: Long = System.currentTimeMillis()): Long? =
        fileFor(id).takeIf { it.isFile && it.length() > 0 }?.let { now - it.lastModified() }

    /** The stored list, or null if there is none or it cannot be read (it is then deleted). */
    fun open(id: String): CatalogDb? {
        val file = fileFor(id)
        if (!file.isFile) return null
        return try {
            CatalogDb.open(file, keyProvider)
        } catch (e: Exception) {
            delete(id)
            null
        }
    }

    /** Writes [catalog] as the stored copy of list [id]; the old copy is replaced only when the new one is complete. */
    fun write(id: String, catalog: Catalog): Boolean {
        dir.mkdirs()
        val temp = File(dir, "tmp-" + System.nanoTime() + ".sqlite")
        return try {
            val db = SQLiteDatabase.openOrCreateDatabase(temp, null)
            try {
                CatalogDb.fill(db, catalog, keyProvider)
            } finally {
                db.close()
            }
            File(temp.path + "-journal").delete()
            val target = fileFor(id)
            target.delete()
            temp.renameTo(target) || run { temp.delete(); false }
        } catch (e: Exception) {
            temp.delete()
            File(temp.path + "-journal").delete()
            false
        }
    }

    fun delete(id: String) {
        val file = fileFor(id)
        runCatching { file.delete() }
        runCatching { File(file.path + "-journal").delete() }
    }

    /**
     * Removes what older versions left in the folder (the text copies `list-*.bin` and the compact snapshots
     * `snap-*`, both replaced by the databases) and half-written files.
     */
    fun cleanUp() {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("snap-") || f.name.startsWith("list-") || f.name.startsWith("tmp-")) f.delete()
            }
        }
    }
}

/** A list read from its database. See [CatalogStore]. */
class CatalogDb private constructor(
    private val rows: Rows,
    live: List<Category>,
    movies: List<Category>,
    series: List<SeriesCategory>,
    epgUrl: String?,
    truncated: Boolean,
    totalEntries: Int,
) : Catalog(live, movies, series, epgUrl, truncated, totalEntries) {

    private fun runsOf(type: ContentType, categories: List<Int>): RowIds.Runs {
        val all = if (type == ContentType.MOVIE) movies else live
        val runs = categories.map { (all[it].items as RowList<*>).ids as RowIds.Runs }
        return RowIds.Runs(
            runs.flatMap { r -> r.ranges.map { it.first } }.toLongArray(),
            runs.flatMap { r -> r.ranges.map { (it.second - it.first + 1).toInt() } }.toIntArray(),
        )
    }

    override fun channels(type: ContentType, categories: List<Int>): List<Channel> {
        if (categories.size == 1) return super.channels(type, categories)
        return RowList(runsOf(type, categories)) { rows.entries(it) }
    }

    override fun shows(categories: List<Int>): List<Show> {
        if (categories.size == 1) return super.shows(categories)
        val runs = categories.map { (series[it].shows as RowList<*>).ids as RowIds.Runs }
        val ids = RowIds.Runs(
            runs.flatMap { r -> r.ranges.map { it.first } }.toLongArray(),
            runs.flatMap { r -> r.ranges.map { (it.second - it.first + 1).toInt() } }.toIntArray(),
        )
        return RowList(ids) { rows.shows(it) }
    }

    override fun sorted(items: List<Channel>, mode: SortMode): List<Channel> {
        val ids = (items as? RowList<*>)?.ids ?: return super.sorted(items, mode)
        return when (mode) {
            SortMode.STANDARD -> items
            SortMode.RECENT -> RowList(RowIds.Reversed(ids)) { rows.entries(it) }
            SortMode.AZ, SortMode.ZA -> {
                val runs = ids as? RowIds.Runs ?: return super.sorted(items, mode)
                RowList(RowIds.Explicit(rows.sortedIds("entries", runs, mode == SortMode.ZA))) { rows.entries(it) }
            }
        }
    }

    override fun sortedShows(items: List<Show>, mode: SortMode): List<Show> {
        val ids = (items as? RowList<*>)?.ids ?: return super.sortedShows(items, mode)
        return when (mode) {
            SortMode.STANDARD -> items
            SortMode.RECENT -> RowList(RowIds.Reversed(ids)) { rows.shows(it) }
            SortMode.AZ, SortMode.ZA -> {
                val runs = ids as? RowIds.Runs ?: return super.sortedShows(items, mode)
                RowList(RowIds.Explicit(rows.sortedIds("shows", runs, mode == SortMode.ZA))) { rows.shows(it) }
            }
        }
    }

    override fun search(type: ContentType, query: String, limit: Int): List<Channel> =
        rows.entries(rows.searchIds("entries", "entry_words", "kind = ${type.ordinal}", "name", query, limit))

    override fun searchShows(query: String, limit: Int): List<Show> =
        rows.shows(rows.searchIds("shows", "show_words", "1", "title", query, limit))

    override fun find(type: ContentType, url: String): Channel? =
        rows.entries(rows.idsWhere("entries", "fkey = ? AND kind = ${type.ordinal}", arrayOf(FavoriteKey.ofUrl(url))))
            .firstOrNull { it.url == url }

    override fun findShow(group: String?, title: String): Show? {
        val ids = if (group == null) rows.idsWhere("shows", "title = ?", arrayOf(title), limit = 1)
        else rows.idsWhere("shows", "title = ? AND grp = ?", arrayOf(title, group), limit = 1)
        return rows.shows(ids).firstOrNull()
    }

    override fun favorites(keys: Set<String>): List<Channel> =
        rows.entries(rows.idsWithKeys("entries", "kind IN (0, 1)", keys))

    override fun favoriteShows(keys: Set<String>): List<Show> =
        rows.shows(rows.idsWithKeys("shows", "1", keys))

    override fun allLive(): List<Channel> = channels(ContentType.LIVE, live.indices.toList())

    override fun close() = rows.close()

    /** Reads rows by number; safe to use from any thread. */
    private class Rows(private val db: SQLiteDatabase, private val dataKey: SecretKey, val fts: Boolean) {

        fun close() {
            runCatching { db.close() }
        }

        fun entries(ids: LongArray): List<Channel> {
            if (ids.isEmpty()) return emptyList()
            val found = HashMap<Long, Channel>(ids.size * 2)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                db.rawQuery(
                    "SELECT id, kind, name, url, grp, logo, tvg_id, tvg_name, title, season, episode FROM entries " +
                        "WHERE id IN (${ids.joinToString(",")})",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        found[id] = Channel(
                            name = c.getString(2).orEmpty(),
                            url = open(cipher, id, c.getBlob(3)),
                            group = c.getString(4),
                            logo = c.getString(5),
                            tvgId = c.getString(6),
                            tvgName = c.getString(7),
                            type = TYPES.getOrElse(c.getInt(1)) { ContentType.LIVE },
                            seriesTitle = c.getString(8),
                            season = c.getInt(9),
                            episode = c.getInt(10),
                        )
                    }
                }
            } catch (e: Exception) {
                // The database was closed (another list was opened) or damaged: show empty rows rather than fail.
            }
            return ids.map { found[it] ?: MISSING }
        }

        fun shows(ids: LongArray): List<Show> {
            if (ids.isEmpty()) return emptyList()
            val found = HashMap<Long, Show>(ids.size * 2)
            try {
                db.rawQuery(
                    "SELECT id, title, logo, grp, first, count FROM shows WHERE id IN (${ids.joinToString(",")})",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val first = c.getLong(4)
                        val count = c.getInt(5)
                        found[c.getLong(0)] = Show(c.getString(1).orEmpty(), c.getString(2), c.getString(3).orEmpty()) {
                            entries(LongArray(count) { first + it })
                        }
                    }
                }
            } catch (e: Exception) {
                // See entries().
            }
            return ids.map { found[it] ?: Show("", null, "", emptyList()) }
        }

        fun sortedIds(table: String, runs: RowIds.Runs, descending: Boolean): LongArray {
            val column = if (table == "shows") "title" else "name"
            val where = runs.ranges.joinToString(" OR ") { "(id BETWEEN ${it.first} AND ${it.second})" }.ifEmpty { "0" }
            val order = if (descending) "DESC" else "ASC"
            return longs("SELECT id FROM $table WHERE $where ORDER BY $column COLLATE NOCASE $order, id", null, runs.size)
        }

        fun idsWhere(table: String, where: String, args: Array<String>, limit: Int = 1000): LongArray =
            longs("SELECT id FROM $table WHERE $where ORDER BY id LIMIT $limit", args, 4)

        fun idsWithKeys(table: String, where: String, keys: Set<String>): LongArray {
            if (keys.isEmpty()) return LongArray(0)
            val all = ArrayList<Long>()
            for (chunk in keys.chunked(400)) {
                val marks = chunk.joinToString(",") { "?" }
                all += longs("SELECT id FROM $table WHERE $where AND fkey IN ($marks)", chunk.toTypedArray(), chunk.size).toList()
            }
            return all.sorted().toLongArray()
        }

        /** Word search first (fast, matches word beginnings); if it finds nothing, any part of the name. */
        fun searchIds(table: String, words: String, where: String, column: String, query: String, limit: Int): LongArray {
            val match = FtsQuery.of(query)
            if (fts && match != null) {
                val hits = try {
                    longs(
                        "SELECT t.id FROM $words JOIN $table t ON t.id = $words.docid WHERE $words MATCH ? AND $where ORDER BY t.id LIMIT $limit",
                        arrayOf(match), limit,
                    )
                } catch (e: Exception) {
                    LongArray(0)
                }
                if (hits.isNotEmpty()) return hits
            }
            val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            return try {
                longs("SELECT id FROM $table WHERE $where AND $column LIKE ? ESCAPE '\\' ORDER BY id LIMIT $limit", arrayOf(like), limit)
            } catch (e: Exception) {
                LongArray(0)
            }
        }

        private fun longs(sql: String, args: Array<String>?, expected: Int): LongArray {
            var out = LongArray(maxOf(16, minOf(expected, 1 shl 20)))
            var n = 0
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    if (n == out.size) out = out.copyOf(out.size * 2)
                    out[n++] = c.getLong(0)
                }
            }
            return out.copyOf(n)
        }

        private fun open(cipher: Cipher, id: Long, sealed: ByteArray?): String {
            if (sealed == null || sealed.size < IV_BYTES + TAG_BYTES) return ""
            return try {
                cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
                cipher.updateAAD(rowTag(id))
                String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
    }

    companion object {
        private const val VERSION = "1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val IV_BYTES = 12
        private val TYPES = ContentType.values()
        private val MISSING = Channel(name = "", url = "")

        /** The row number is bound to each sealed address, so addresses cannot be swapped between rows. */
        private fun rowTag(id: Long): ByteArray = ByteBuffer.allocate(8).putLong(id).array()

        fun open(file: File, keyProvider: () -> SecretKey): CatalogDb {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val meta = HashMap<String, ByteArray>()
                db.rawQuery("SELECT k, v FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0).orEmpty()] = c.getBlob(1) ?: ByteArray(0) }
                fun text(key: String): String? = meta[key]?.let { String(it, Charsets.UTF_8) }
                check(text("version") == VERSION) { "other version" }
                val dataKey = unwrap(meta["key"] ?: error("no key"), keyProvider())
                val rows = Rows(db, dataKey, text("fts") == "1")

                val live = ArrayList<Category>()
                val movies = ArrayList<Category>()
                val series = ArrayList<SeriesCategory>()
                db.rawQuery("SELECT kind, name, first, count FROM cats ORDER BY kind, idx", null).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1).orEmpty()
                        val ids = RowIds.Runs(longArrayOf(c.getLong(2)), intArrayOf(c.getInt(3)))
                        when (c.getInt(0)) {
                            ContentType.LIVE.ordinal -> live += Category(name, RowList(ids) { rows.entries(it) })
                            ContentType.MOVIE.ordinal -> movies += Category(name, RowList(ids) { rows.entries(it) })
                            else -> series += SeriesCategory(name, RowList(ids) { rows.shows(it) })
                        }
                    }
                }
                return CatalogDb(
                    rows, live, movies, series,
                    epgUrl = text("epg")?.ifEmpty { null },
                    truncated = text("truncated") == "1",
                    totalEntries = text("total")?.toIntOrNull() ?: 0,
                )
            } catch (e: Exception) {
                db.close()
                throw e
            }
        }

        fun fill(db: SQLiteDatabase, catalog: Catalog, keyProvider: () -> SecretKey) {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val dataKey = SecretKeySpec(raw, "AES")
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v BLOB)")
                db.execSQL("CREATE TABLE cats(kind INTEGER, idx INTEGER, name TEXT, first INTEGER, count INTEGER)")
                db.execSQL(
                    "CREATE TABLE entries(id INTEGER PRIMARY KEY, kind INTEGER, name TEXT, url BLOB, grp TEXT, logo TEXT, " +
                        "tvg_id TEXT, tvg_name TEXT, title TEXT, season INTEGER, episode INTEGER, fkey TEXT)",
                )
                db.execSQL("CREATE TABLE shows(id INTEGER PRIMARY KEY, title TEXT, logo TEXT, grp TEXT, first INTEGER, count INTEGER, fkey TEXT)")
                val fts = try {
                    db.execSQL("CREATE VIRTUAL TABLE entry_words USING fts4(name)")
                    db.execSQL("CREATE VIRTUAL TABLE show_words USING fts4(title)")
                    true
                } catch (e: Exception) {
                    false
                }

                val addMeta = db.compileStatement("INSERT INTO meta VALUES(?, ?)")
                fun metaBytes(key: String, value: ByteArray) {
                    addMeta.clearBindings(); addMeta.bindString(1, key); addMeta.bindBlob(2, value); addMeta.executeInsert()
                }
                fun metaText(key: String, value: String) = metaBytes(key, value.toByteArray(Charsets.UTF_8))
                metaText("version", VERSION)
                metaBytes("key", wrap(raw, keyProvider()))
                metaText("epg", catalog.epgUrl.orEmpty())
                metaText("truncated", if (catalog.truncated) "1" else "0")
                metaText("total", catalog.totalEntries.toString())
                metaText("fts", if (fts) "1" else "0")

                val addCat = db.compileStatement("INSERT INTO cats VALUES(?, ?, ?, ?, ?)")
                val addEntry = db.compileStatement("INSERT INTO entries VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                val addShow = db.compileStatement("INSERT INTO shows VALUES(?, ?, ?, ?, ?, ?, ?)")
                val addWord = if (fts) db.compileStatement("INSERT INTO entry_words(docid, name) VALUES(?, ?)") else null
                val addTitle = if (fts) db.compileStatement("INSERT INTO show_words(docid, title) VALUES(?, ?)") else null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                var nextEntry = 1L
                var nextShow = 1L

                fun cat(kind: ContentType, index: Int, name: String, first: Long, count: Int) {
                    addCat.clearBindings()
                    addCat.bindLong(1, kind.ordinal.toLong()); addCat.bindLong(2, index.toLong()); addCat.bindString(3, name)
                    addCat.bindLong(4, first); addCat.bindLong(5, count.toLong())
                    addCat.executeInsert()
                }

                fun entry(c: Channel, words: Boolean) {
                    val id = nextEntry++
                    cipher.init(Cipher.ENCRYPT_MODE, dataKey)
                    val iv = cipher.iv
                    check(iv != null && iv.size == IV_BYTES) { "unexpected IV" }
                    cipher.updateAAD(rowTag(id))
                    val sealed = iv + cipher.doFinal(c.url.toByteArray(Charsets.UTF_8))
                    addEntry.clearBindings()
                    addEntry.bindLong(1, id)
                    addEntry.bindLong(2, c.type.ordinal.toLong())
                    addEntry.bindString(3, c.name)
                    addEntry.bindBlob(4, sealed)
                    c.group?.let { addEntry.bindString(5, it) }
                    c.logo?.let { addEntry.bindString(6, it) }
                    c.tvgId?.let { addEntry.bindString(7, it) }
                    c.tvgName?.let { addEntry.bindString(8, it) }
                    c.seriesTitle?.let { addEntry.bindString(9, it) }
                    addEntry.bindLong(10, c.season.toLong())
                    addEntry.bindLong(11, c.episode.toLong())
                    addEntry.bindString(12, FavoriteKey.of(c))
                    addEntry.executeInsert()
                    if (words && addWord != null) {
                        addWord.clearBindings(); addWord.bindLong(1, id); addWord.bindString(2, c.name); addWord.executeInsert()
                    }
                }

                for ((type, categories) in listOf(ContentType.LIVE to catalog.live, ContentType.MOVIE to catalog.movies)) {
                    categories.forEachIndexed { index, category ->
                        val first = nextEntry
                        for (c in category.items) entry(c, words = true)
                        cat(type, index, category.name, first, (nextEntry - first).toInt())
                    }
                }
                catalog.series.forEachIndexed { index, category ->
                    val firstShow = nextShow
                    for (show in category.shows) {
                        val first = nextEntry
                        for (episode in show.episodes) entry(episode, words = false)
                        val id = nextShow++
                        addShow.clearBindings()
                        addShow.bindLong(1, id)
                        addShow.bindString(2, show.title)
                        show.logo?.let { addShow.bindString(3, it) }
                        addShow.bindString(4, show.group)
                        addShow.bindLong(5, first)
                        addShow.bindLong(6, nextEntry - first)
                        addShow.bindString(7, FavoriteKey.ofShow(show))
                        addShow.executeInsert()
                        if (addTitle != null) {
                            addTitle.clearBindings(); addTitle.bindLong(1, id); addTitle.bindString(2, show.title); addTitle.executeInsert()
                        }
                    }
                    cat(ContentType.SERIES, index, category.name, firstShow, (nextShow - firstShow).toInt())
                }

                db.execSQL("CREATE INDEX entries_fkey ON entries(fkey)")
                db.execSQL("CREATE INDEX shows_fkey ON shows(fkey)")
                db.execSQL("CREATE INDEX shows_title ON shows(title)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private fun wrap(raw: ByteArray, key: SecretKey): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val sealed = cipher.doFinal(raw)
            return byteArrayOf(iv.size.toByte()) + iv + sealed
        }

        private fun unwrap(stored: ByteArray, key: SecretKey): SecretKey {
            val ivLength = stored[0].toInt() and 0xFF
            require(ivLength in 12..32 && stored.size > 1 + ivLength + TAG_BYTES) { "bad key" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored, 1, ivLength))
            return SecretKeySpec(cipher.doFinal(stored, 1 + ivLength, stored.size - 1 - ivLength), "AES")
        }
    }
}

/**
 * Turns what the user typed into an FTS4 query that finds names containing words that start with each typed
 * word ("sky cin" finds "Sky Cinema Uno"). The words are split like SQLite's simple tokenizer does (any ASCII
 * character that is not a letter or digit separates words) and lower-cased, so no typed text is read as an
 * operator. Null when nothing searchable was typed.
 */
object FtsQuery {
    fun of(text: String): String? {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch.code < 128 && !ch.isLetterOrDigit()) {
                if (current.isNotEmpty()) { words += current.toString(); current.clear() }
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        if (words.isEmpty()) return null
        return words.take(8).joinToString(" ") { "$it*" }
    }
}
