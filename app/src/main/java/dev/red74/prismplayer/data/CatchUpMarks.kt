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

    /** True when this channel, or its whole provider, has shown no archive: the button is not offered. */
    fun noArchive(url: String): Boolean {
        val live = CatchUp.liveMarks(marks, System.currentTimeMillis())
        return CatchUp.markKey(url) in live || CatchUp.providerHasNoArchive(live, url)
    }

    fun mark(url: String) {
        val now = System.currentTimeMillis()
        var next = CatchUp.liveMarks(marks, now) + (CatchUp.markKey(url) to now)
        CatchUp.failureKey(url)?.let { next = next + (it to now) }
        save(next)
    }

    /** A programme opened from its start: the provider has an archive, so the button stays on its channels. */
    fun worked(url: String) {
        val key = CatchUp.workedKey(url) ?: return
        val now = System.currentTimeMillis()
        save(CatchUp.liveMarks(marks, now) + (key to now))
    }

    private fun save(next: Map<String, Long>) {
        marks = next
        prefs?.edit()?.putStringSet("marks", Availability.encode(next))?.apply()
    }
}
