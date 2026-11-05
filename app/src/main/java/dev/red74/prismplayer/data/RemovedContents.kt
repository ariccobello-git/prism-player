package dev.red74.prismplayer.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** A channel, film or series that a list no longer has. Only its name and group are kept, never its address. */
data class RemovedItem(val type: ContentType, val name: String, val group: String?)

/** What one renewal of a list took away: at most [RemovedContents.MAX_ITEMS] items are kept of [total]. */
data class RemovedBatch(
    val listId: String,
    val listName: String,
    val at: Long,
    val total: Int,
    val items: List<RemovedItem>,
)

/** What changed between the stored copy of a list and its new download (see [ListDiff]). */
class ListChanges(
    val removed: List<RemovedItem>,
    val removedTotal: Int,
    val addedTotal: Int,
    /** Keys (see [FavoriteKey]) among the ones asked about that were in the old copy and are not in the new one. */
    val goneKeys: Set<String>,
) {
    val changed: Boolean get() = removedTotal > 0 || addedTotal > 0
}

object RemovedContents {
    /** Records older than this are deleted by themselves. */
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    /** Items listed for one renewal; a bigger loss is only counted. */
    const val MAX_ITEMS = 2_000
    /** Items kept in all (oldest renewals go first), so the file stays small. */
    const val MAX_STORED = 10_000

    /**
     * True when so much of a list vanished at once that the provider most likely changed its addresses (for example
     * a new password inside every link) rather than removing contents: nothing is recorded and nothing is cleaned.
     */
    fun looksLikeNewAddresses(oldCount: Int, removedCount: Int): Boolean =
        oldCount >= 200 && removedCount * 2 > oldCount
}

/**
 * Compares the new copy of a list (being written) with the stored one, inside SQLite: the stored copy is attached to
 * the new database and the rows are matched by their keys, which are indexed in both, so even a list of hundreds of
 * thousands of entries is compared without loading it in memory. Channels and films are matched by address key,
 * series by title (a series moved to another group is not a removal).
 */
object ListDiff {
    fun compare(
        db: SQLiteDatabase,
        oldFile: File,
        askedKeys: Set<String>,
        currentVersion: String,
    ): ListChanges? {
        db.execSQL("ATTACH DATABASE ? AS old", arrayOf<Any>(oldFile.path))
        try {
            val oldVersion = db.rawQuery("SELECT v FROM old.meta WHERE k = 'version'", null).use { c ->
                if (c.moveToFirst()) c.getBlob(0)?.let { String(it, Charsets.UTF_8) } else null
            }
            // Series titles were cleaned differently by older versions: comparing them would report false removals.
            val compareShows = oldVersion == currentVersion

            val oldEntries = count(db, "SELECT COUNT(*) FROM old.entries WHERE kind IN (0, 1)")
            val oldShows = if (compareShows) count(db, "SELECT COUNT(*) FROM old.shows") else 0

            val removed = ArrayList<RemovedItem>()
            var removedEntries = 0
            db.rawQuery(
                "SELECT o.kind, o.name, o.grp FROM old.entries o WHERE o.kind IN (0, 1) AND " +
                    "NOT EXISTS (SELECT 1 FROM main.entries n WHERE n.fkey = o.fkey) ORDER BY o.id",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    removedEntries++
                    if (removed.size < RemovedContents.MAX_ITEMS) {
                        val type = if (c.getInt(0) == ContentType.MOVIE.ordinal) ContentType.MOVIE else ContentType.LIVE
                        removed += RemovedItem(type, c.getString(1).orEmpty(), c.getString(2))
                    }
                }
            }
            var removedShows = 0
            if (compareShows) {
                db.rawQuery(
                    "SELECT o.title, o.grp FROM old.shows o WHERE " +
                        "NOT EXISTS (SELECT 1 FROM main.shows n WHERE n.title = o.title) ORDER BY o.id",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        removedShows++
                        if (removed.size < RemovedContents.MAX_ITEMS) {
                            removed += RemovedItem(ContentType.SERIES, c.getString(0).orEmpty(), c.getString(1))
                        }
                    }
                }
            }
            if (RemovedContents.looksLikeNewAddresses(oldEntries, removedEntries) ||
                RemovedContents.looksLikeNewAddresses(oldShows, removedShows)
            ) {
                return null
            }

            val added = count(
                db,
                "SELECT COUNT(*) FROM main.entries n WHERE n.kind IN (0, 1) AND " +
                    "NOT EXISTS (SELECT 1 FROM old.entries o WHERE o.fkey = n.fkey)",
            ) + if (compareShows) {
                count(db, "SELECT COUNT(*) FROM main.shows n WHERE NOT EXISTS (SELECT 1 FROM old.shows o WHERE o.title = n.title)")
            } else {
                0
            }

            val gone = HashSet<String>()
            for (chunk in askedKeys.chunked(400)) {
                val marks = chunk.joinToString(",") { "?" }
                val args = (chunk + chunk).toTypedArray()
                val before = keys(db, "SELECT fkey FROM old.entries WHERE fkey IN ($marks) UNION SELECT fkey FROM old.shows WHERE fkey IN ($marks)", args)
                if (before.isEmpty()) continue
                val now = keys(db, "SELECT fkey FROM main.entries WHERE fkey IN ($marks) UNION SELECT fkey FROM main.shows WHERE fkey IN ($marks)", args)
                gone += before - now
            }
            return ListChanges(removed, removedEntries + removedShows, added, gone)
        } finally {
            runCatching { db.execSQL("DETACH DATABASE old") }
        }
    }

    private fun count(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun keys(db: SQLiteDatabase, sql: String, args: Array<String>): Set<String> {
        val out = HashSet<String>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) c.getString(0)?.let(out::add) }
        return out
    }
}

/**
 * The record of removed contents, in a small database of its own in the app's private, not-backed-up folder.
 * Every record goes away by itself after 7 days ([RemovedContents.KEEP_MS]). Safe to use from any thread.
 */
class RemovedStore(private val file: File) {
    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun db(): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        file.parentFile?.mkdirs()
        val opened = try {
            SQLiteDatabase.openOrCreateDatabase(file, null)
        } catch (e: Exception) {
            file.delete()
            SQLiteDatabase.openOrCreateDatabase(file, null)
        }
        opened.execSQL("CREATE TABLE IF NOT EXISTS batches(id INTEGER PRIMARY KEY, list_id TEXT, list_name TEXT, at INTEGER, total INTEGER)")
        opened.execSQL("CREATE TABLE IF NOT EXISTS items(batch INTEGER, kind INTEGER, name TEXT, grp TEXT)")
        opened.execSQL("CREATE INDEX IF NOT EXISTS items_batch ON items(batch)")
        db = opened
        return opened
    }

    @Synchronized
    fun add(listId: String, listName: String, at: Long, total: Int, items: List<RemovedItem>) {
        if (total <= 0) return
        runCatching {
            val db = db()
            db.beginTransaction()
            try {
                val batch = db.compileStatement("INSERT INTO batches(list_id, list_name, at, total) VALUES(?, ?, ?, ?)").run {
                    bindString(1, listId); bindString(2, listName); bindLong(3, at); bindLong(4, total.toLong())
                    executeInsert()
                }
                val add = db.compileStatement("INSERT INTO items VALUES(?, ?, ?, ?)")
                for (item in items) {
                    add.clearBindings()
                    add.bindLong(1, batch)
                    add.bindLong(2, item.type.ordinal.toLong())
                    add.bindString(3, item.name)
                    item.group?.let { add.bindString(4, it) }
                    add.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            trim(db, at)
        }
    }

    /** Deletes what is older than 7 days, and the oldest renewals when there are too many items. */
    @Synchronized
    fun purge(now: Long = System.currentTimeMillis()) {
        if (!file.isFile) return
        runCatching { trim(db(), now) }
    }

    private fun trim(db: SQLiteDatabase, now: Long) {
        db.execSQL("DELETE FROM batches WHERE at < ? OR at > ?", arrayOf<Any>(now - RemovedContents.KEEP_MS, now + 60_000))
        var stored = db.rawQuery("SELECT COUNT(*) FROM items", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        while (stored > RemovedContents.MAX_STORED) {
            val oldest = db.rawQuery("SELECT id FROM batches ORDER BY at, id LIMIT 1", null).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                ?: break
            db.execSQL("DELETE FROM batches WHERE id = ?", arrayOf<Any>(oldest))
            db.execSQL("DELETE FROM items WHERE batch = ?", arrayOf<Any>(oldest))
            stored = db.rawQuery("SELECT COUNT(*) FROM items", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
        db.execSQL("DELETE FROM items WHERE batch NOT IN (SELECT id FROM batches)")
    }

    /** How many contents were removed in the last 7 days. */
    @Synchronized
    fun count(now: Long = System.currentTimeMillis()): Int {
        if (!file.isFile) return 0
        return runCatching {
            db().rawQuery("SELECT COALESCE(SUM(total), 0) FROM batches WHERE at >= ?", arrayOf((now - RemovedContents.KEEP_MS).toString()))
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)
    }

    /** The renewals of the last 7 days, newest first. */
    @Synchronized
    fun batches(now: Long = System.currentTimeMillis()): List<RemovedBatch> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val db = db()
            trim(db, now)
            val heads = ArrayList<Pair<Long, RemovedBatch>>()
            db.rawQuery("SELECT id, list_id, list_name, at, total FROM batches ORDER BY at DESC, id DESC", null).use { c ->
                while (c.moveToNext()) {
                    heads += c.getLong(0) to RemovedBatch(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getInt(4), emptyList())
                }
            }
            heads.map { (id, head) ->
                val items = ArrayList<RemovedItem>()
                db.rawQuery("SELECT kind, name, grp FROM items WHERE batch = ? ORDER BY rowid", arrayOf(id.toString())).use { c ->
                    while (c.moveToNext()) {
                        val type = ContentType.values().getOrElse(c.getInt(0)) { ContentType.LIVE }
                        items += RemovedItem(type, c.getString(1).orEmpty(), c.getString(2))
                    }
                }
                head.copy(items = items)
            }
        }.getOrDefault(emptyList())
    }

    /** A list deleted from the app takes its records with it. */
    @Synchronized
    fun deleteList(listId: String) {
        if (!file.isFile) return
        runCatching {
            val db = db()
            db.execSQL("DELETE FROM batches WHERE list_id = ?", arrayOf<Any>(listId))
            db.execSQL("DELETE FROM items WHERE batch NOT IN (SELECT id FROM batches)")
        }
    }
}
