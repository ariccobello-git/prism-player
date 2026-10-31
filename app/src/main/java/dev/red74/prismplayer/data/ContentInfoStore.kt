package dev.red74.prismplayer.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The panel's answers kept on the stick: one small table, one row per film, per series of a panel and per episode
 * (keys in [InfoKeys]). A lookup is a single indexed read, so a series page never loads the whole series list.
 * Plots are not secret and the keys are hashes, so nothing here reveals the list's address.
 */
class ContentInfoStore(private val file: File) {
    /**
     * Up to three texts (film: plot; series: id, plot; episode: plot, length, date) and when they were saved.
     * [d] holds the picture and audio badges of a film or episode ("FHD|HEVC|ITA"); null in rows saved before
     * the badges existed, empty when the panel had none.
     */
    class Row(val a: String?, val b: String?, val c: String?, val savedAt: Long, val d: String? = null)

    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS info(k TEXT PRIMARY KEY, a TEXT, b TEXT, c TEXT, t INTEGER NOT NULL, d TEXT) WITHOUT ROWID")
            if (version < 1) {
                // Tables made before the badges have no "d" column (a new one already has it).
                runCatching { execSQL("ALTER TABLE info ADD COLUMN d TEXT") }
                version = 1
            }
        }
    }

    fun get(key: String): Row? =
        db.rawQuery("SELECT a, b, c, t, d FROM info WHERE k = ?", arrayOf(key)).use { c ->
            if (!c.moveToFirst()) null
            else Row(c.getStringOrNull(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getLong(3), c.getStringOrNull(4))
        }

    fun put(key: String, row: Row) {
        db.compileStatement(INSERT).use { insert ->
            bind(insert, key, row.a, row.b, row.c, row.savedAt, row.d)
            insert.executeInsert()
        }
    }

    /** Replaces every row of [group] with [rows] (keyed by item) and stamps the group's marker, all at once. */
    fun replaceGroup(group: String, rows: Map<String, Row>, savedAt: Long = System.currentTimeMillis()) {
        db.beginTransaction()
        try {
            db.delete("info", "k >= ? AND k < ?", arrayOf(InfoKeys.row(group, ""), InfoKeys.groupEnd(group)))
            db.compileStatement(INSERT).use { insert ->
                for ((item, row) in rows) {
                    bind(insert, InfoKeys.row(group, item), row.a, row.b, row.c, savedAt, row.d)
                    insert.executeInsert()
                }
                bind(insert, group, null, null, null, savedAt, null)
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun bind(insert: android.database.sqlite.SQLiteStatement, key: String, a: String?, b: String?, c: String?, t: Long, d: String?) {
        insert.clearBindings()
        insert.bindString(1, key)
        if (a != null) insert.bindString(2, a) else insert.bindNull(2)
        if (b != null) insert.bindString(3, b) else insert.bindNull(3)
        if (c != null) insert.bindString(4, c) else insert.bindNull(4)
        insert.bindLong(5, t)
        if (d != null) insert.bindString(6, d) else insert.bindNull(6)
    }

    private companion object {
        const val INSERT = "INSERT OR REPLACE INTO info(k, a, b, c, t, d) VALUES(?, ?, ?, ?, ?, ?)"
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
