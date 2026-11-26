package dev.red74.prismplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The viewer's favourite sports and the teams or competitions they follow (free words such as "Roma" or "Formula 1"). */
data class SportPrefs(val topics: Set<SportTopic> = emptySet(), val keywords: List<String> = emptyList()) {
    val isEmpty: Boolean get() = topics.isEmpty() && keywords.isEmpty()

    /** True when the event is of a favourite sport or its title has a followed word as a whole word. */
    fun matches(event: HighlightEvent): Boolean {
        if (event.topic != null && event.topic in topics) return true
        if (keywords.isEmpty()) return false
        val text = " " + Highlights.clean(event.title) + " "
        return keywords.any { text.contains(" $it ") }
    }

    /**
     * The "Followed teams" page: only sport events whose title has one of the followed teams (or words) as a whole
     * word. Following a whole sport does not bring every event of that sport here, and no other kind of programme.
     */
    fun followsTeam(event: HighlightEvent): Boolean {
        if (event.kind != HighlightKind.SPORT || keywords.isEmpty()) return false
        val text = " " + Highlights.clean(event.title) + " "
        return keywords.any { text.contains(" $it ") }
    }

    fun withTopic(topic: SportTopic, on: Boolean): SportPrefs = copy(topics = if (on) topics + topic else topics - topic)

    /** Adds a followed word (kept in its cleaned form); empty, repeated or beyond [MAX_KEYWORDS] words are ignored. */
    fun withKeyword(text: String): SportPrefs {
        val word = Highlights.clean(text).take(MAX_LENGTH).trim()
        if (word.isEmpty() || word in keywords || keywords.size >= MAX_KEYWORDS) return this
        return copy(keywords = keywords + word)
    }

    fun withoutKeyword(word: String): SportPrefs = copy(keywords = keywords - word)

    fun encode(): String = topics.joinToString(",") { it.name } + "\n" + keywords.joinToString("\n")

    companion object {
        const val MAX_KEYWORDS = 24
        const val MAX_LENGTH = 30

        fun decode(text: String?): SportPrefs {
            if (text.isNullOrEmpty()) return SportPrefs()
            val lines = text.split('\n')
            val topics = lines.first().split(',').mapNotNull { name -> SportTopic.entries.firstOrNull { it.name == name } }.toSet()
            val keywords = lines.drop(1).map { Highlights.clean(it).take(MAX_LENGTH).trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)
            return SportPrefs(topics, keywords)
        }
    }
}

/** Keeps [SportPrefs] on the device; only the viewer's own choices, nothing about the playlist. */
object SportPrefsStore {
    private var prefs: SharedPreferences? = null

    /** The current choices; screens read it and redraw when it changes. */
    var current by mutableStateOf(SportPrefs())
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("sport_prefs", Context.MODE_PRIVATE)
        prefs = store
        current = SportPrefs.decode(store.getString("prefs", null))
    }

    fun update(change: (SportPrefs) -> SportPrefs) {
        val next = change(current)
        if (next == current) return
        current = next
        prefs?.edit()?.putString("prefs", next.encode())?.apply()
    }
}
