package dev.red74.prismplayer.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The panel's answers kept on the stick: one small table, one row per film, per series of a panel and per episode
 * (keys in [InfoKeys]). A lookup is a single indexed read, so a series page never loads the whole series list.
 * Plots are not secret and the keys are hashes, so nothing here reveals the list's address.
 */
class ContentInfoStore(private val file: File) {
    /** Up to three texts (film: plot; series: id, plot; episode: plot, length, date) and when they were saved. */
    class Row(val a: String?, val b: String?, val c: String?, val savedAt: Long)

    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS info(k TEXT PRIMARY KEY, a TEXT, b TEXT, c TEXT, t INTEGER NOT NULL) WITHOUT ROWID")
        }
    }

    fun get(key: String): Row? =
        db.rawQuery("SELECT a, b, c, t FROM info WHERE k = ?", arrayOf(key)).use { c ->
            if (!c.moveToFirst()) null
            else Row(c.getStringOrNull(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getLong(3))
        }

    fun put(key: String, row: Row) {
        db.compileStatement("INSERT OR REPLACE INTO info VALUES(?, ?, ?, ?, ?)").use { insert ->
            bind(insert, key, row.a, row.b, row.c, row.savedAt)
            insert.executeInsert()
        }
    }

    /** Replaces every row of [group] with [rows] (keyed by item) and stamps the group's marker, all at once. */
    fun replaceGroup(group: String, rows: Map<String, Row>, savedAt: Long = System.currentTimeMillis()) {
        db.beginTransaction()
        try {
            db.delete("info", "k >= ? AND k < ?", arrayOf(InfoKeys.row(group, ""), InfoKeys.groupEnd(group)))
            db.compileStatement("INSERT OR REPLACE INTO info VALUES(?, ?, ?, ?, ?)").use { insert ->
                for ((item, row) in rows) {
                    bind(insert, InfoKeys.row(group, item), row.a, row.b, row.c, savedAt)
                    insert.executeInsert()
                }
                bind(insert, group, null, null, null, savedAt)
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun bind(insert: android.database.sqlite.SQLiteStatement, key: String, a: String?, b: String?, c: String?, t: Long) {
        insert.clearBindings()
        insert.bindString(1, key)
        if (a != null) insert.bindString(2, a) else insert.bindNull(2)
        if (b != null) insert.bindString(3, b) else insert.bindNull(3)
        if (c != null) insert.bindString(4, c) else insert.bindNull(4)
        insert.bindLong(5, t)
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
