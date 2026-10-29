package dev.red74.prismplayer.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.red74.prismplayer.R

/**
 * Short interface sounds (own synthesized tones). They are off in the player and while the app closes, and a
 * held-down direction key only sounds once: [play] for MOVE is called by the activity on the first key-down only,
 * never on auto-repeat, so the sound never runs out of step with the cursor.
 */
object UiSounds {
    enum class Kind(val volume: Float) { MOVE(0.45f), FORWARD(0.8f), BACK(0.8f), SELECT(0.85f), DESELECT(0.75f), ERROR(0.9f) }

    @Volatile
    var enabled: Boolean = false

    /** Everything the app plays (interface sounds, opening and closing music) is 30% below full scale. */
    const val MASTER = 0.7f

    /** Set from the Home menu; remembered between launches. */
    @Volatile
    var muted: Boolean = false
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun setMuted(value: Boolean) {
        muted = value
        runCatching { prefs?.edit()?.putBoolean("muted", value)?.apply() }
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Kind, Int>()
    private val ready = HashSet<Int>()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("prism_ui", Context.MODE_PRIVATE)
            muted = runCatching { prefs?.getBoolean("muted", false) }.getOrNull() ?: false
        }
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
        created.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready.add(id) } }
        val app = context.applicationContext
        ids[Kind.MOVE] = created.load(app, R.raw.ui_move, 1)
        ids[Kind.FORWARD] = created.load(app, R.raw.ui_forward, 1)
        ids[Kind.BACK] = created.load(app, R.raw.ui_back, 1)
        ids[Kind.SELECT] = created.load(app, R.raw.ui_select, 1)
        ids[Kind.DESELECT] = created.load(app, R.raw.ui_deselect, 1)
        ids[Kind.ERROR] = created.load(app, R.raw.ui_error, 1)
        pool = created
    }

    fun play(kind: Kind) {
        if (!enabled || muted) return
        val id = ids[kind] ?: return
        if (!synchronized(ready) { id in ready }) return
        runCatching { pool?.play(id, kind.volume * MASTER, kind.volume * MASTER, 1, 0, 1f) }
    }
}
