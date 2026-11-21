package dev.red74.prismplayer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The channels that answered to a catch-up request with an error (no archive), kept for a week (see [CatchUp]) so the
 * "from the start" button is not offered again on them. Hashed keys and the time only.
 */
object CatchUpMarks {
    private var prefs: SharedPreferences? = null
    private var marks: Map<String, Long> = emptyMap()

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("catchup_marks", Context.MODE_PRIVATE)
        prefs = store
        marks = CatchUp.liveMarks(Availability.decode(store.getStringSet("marks", emptySet()).orEmpty()), System.currentTimeMillis())
    }

    fun isMarked(url: String): Boolean = CatchUp.markKey(url) in CatchUp.liveMarks(marks, System.currentTimeMillis())

    fun mark(url: String) {
        val now = System.currentTimeMillis()
        marks = CatchUp.liveMarks(marks, now) + (CatchUp.markKey(url) to now)
        prefs?.edit()?.putStringSet("marks", Availability.encode(marks))?.apply()
    }
}
