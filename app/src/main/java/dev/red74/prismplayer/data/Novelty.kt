package dev.red74.prismplayer.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * When each film and series came into a list. Lists rarely say it (M3U files never do), so the app notes the day it
 * first finds a content on a renewal of the stored copy: a film that is in the new download and not in the old copy
 * is new from now. Only these new ones are written (key and time), and they are forgotten after [WINDOW_MS].
 *
 * The very first copy of a list has nothing to compare with, so nothing in it counts as new; and when most of a list
 * looks new at once (the provider changed every address), that is not news either and nothing is marked.
 */
object Novelty {
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    const val CREATE = "CREATE TABLE seen(fkey TEXT PRIMARY KEY, at INTEGER NOT NULL)"

    /** Fills the "seen" table of the new copy [db] from the old copy [oldFile]; [now] is the time of the renewal. */
    fun carry(db: SQLiteDatabase, oldFile: File, now: Long) {
        db.execSQL("ATTACH DATABASE ? AS prev", arrayOf<Any>(oldFile.path))
        try {
            db.beginTransaction()
            try {
                if (hasTable(db, "prev", "seen")) {
                    db.execSQL("INSERT OR IGNORE INTO seen SELECT fkey, at FROM prev.seen WHERE at >= ?", arrayOf<Any>(now - WINDOW_MS))
                }
                val movie = ContentType.MOVIE.ordinal
                val oldFilms = count(db, "SELECT COUNT(*) FROM prev.entries WHERE kind = $movie")
                val newFilms = count(db, "SELECT COUNT(*) FROM entries WHERE kind = $movie AND fkey NOT IN (SELECT fkey FROM prev.entries)")
                if (isNews(oldFilms, newFilms)) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO seen SELECT fkey, ? FROM entries WHERE kind = $movie AND fkey NOT IN (SELECT fkey FROM prev.entries)",
                        arrayOf<Any>(now),
                    )
                }
                // A series is the same one when its title is (moving to another group is not news).
                val oldShows = count(db, "SELECT COUNT(*) FROM prev.shows")
                val newShows = count(db, "SELECT COUNT(*) FROM shows WHERE title NOT IN (SELECT title FROM prev.shows)")
                if (isNews(oldShows, newShows)) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO seen SELECT fkey, ? FROM shows WHERE title NOT IN (SELECT title FROM prev.shows)",
                        arrayOf<Any>(now),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.execSQL("DETACH DATABASE prev")
        }
    }

    /**
     * New contents count as news only against a real old copy, and when they are a handful or less than half of the
     * list (more than that at once means the provider changed the addresses, not the contents).
     */
    fun isNews(oldCount: Long, newCount: Long): Boolean =
        oldCount > 0 && newCount > 0 && (newCount <= FEW || newCount * 2 <= oldCount)

    private const val FEW = 50L

    private fun hasTable(db: SQLiteDatabase, schema: String, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM $schema.sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
