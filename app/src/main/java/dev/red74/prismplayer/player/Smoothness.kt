package dev.red74.prismplayer.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Tracks

/**
 * Picks the screen refresh rate that shows a video of [fps] frames per second without judder: one rate that is a
 * whole multiple of it (24 fps on 24, 48 or 72 Hz; 25 fps on 50 Hz; 29.97 fps on 59.94 Hz), at the size the screen
 * already uses, so there is no change of picture size. Among equally good rates the highest wins: the menus stay smooth.
 */
object FrameRateMatch {
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** Largest relative gap between the screen rate and a multiple of the video rate that still looks smooth. */
    const val TOLERANCE = 0.003f

    /** Two rates this close to a multiple of the video rate count as equally good. */
    private const val SAME = 0.0002f

    /** The id of the mode to ask for, or null when the current one is already as good or no mode fits. */
    fun pick(modes: List<Mode>, current: Mode, fps: Float): Int? {
        if (fps < 10f || fps > 120f) return null
        val candidates = modes.filter { it.width == current.width && it.height == current.height && it.refreshRate > 0f }
        if (candidates.isEmpty()) return null
        fun error(mode: Mode): Float = (1..5).minOf { m -> kotlin.math.abs(mode.refreshRate - fps * m) / mode.refreshRate }
        val best = candidates.minWith(compareBy<Mode> { if (error(it) <= TOLERANCE) 0 else 1 }.thenBy { error(it) }.thenByDescending { it.refreshRate })
        if (error(best) > TOLERANCE) return null
        // The current mode is kept when it fits as well as the best one (30 fps on 60 Hz is already even).
        if (error(current) <= error(best) + SAME) return null
        return if (best.id == current.id) null else best.id
    }
}

/** The viewer's choice to follow the video's frame rate (off by default: some TVs blank for a second when it changes). */
object FrameRatePref {
    private var prefs: SharedPreferences? = null

    var enabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
        prefs = store
        enabled = store.getBoolean("match_frame_rate", false)
    }

    fun set(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean("match_frame_rate", value)?.apply()
    }
}

/** The viewer's choice of a steadier playback: always the longer cushion of [BufferPlan.STEADY] (off = automatic). */
object BufferPref {
    private var prefs: SharedPreferences? = null

    var steady by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
        prefs = store
        steady = store.getBoolean("steady_buffer", false)
    }

    fun set(value: Boolean) {
        steady = value
        prefs?.edit()?.putBoolean("steady_buffer", value)?.apply()
    }
}

/** Asks the screen for the rate that suits the playing video, and gives the choice back when playback ends. */
object DisplayRate {
    private var changed = false

    fun apply(context: Context, tracks: Tracks) {
        if (!FrameRatePref.enabled) return
        val fps = videoFrameRate(tracks) ?: return
        val activity = activityOf(context) ?: return
        val display = activity.window.decorView.display ?: return
        val current = display.mode.let { FrameRateMatch.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val modes = display.supportedModes.map { FrameRateMatch.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val id = FrameRateMatch.pick(modes, current, fps) ?: return
        set(activity, id)
        changed = true
    }

    fun restore(context: Context) {
        if (!changed) return
        changed = false
        activityOf(context)?.let { set(it, 0) }
    }

    private fun set(activity: Activity, modeId: Int) {
        val attributes: WindowManager.LayoutParams = activity.window.attributes
        attributes.preferredDisplayModeId = modeId
        activity.window.attributes = attributes
    }

    private fun videoFrameRate(tracks: Tracks): Float? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val rate = group.getTrackFormat(i).frameRate
                    if (rate > 0f) return rate
                }
            }
        }
        return null
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/**
 * Remembers recent rebuffering. A viewer whose playback stalled several times in a short while gets a bigger
 * cushion for the next streams (see [BufferPlan.STEADY]): resuming after more seconds means fewer repeated stops.
 */
class StallLog(private val windowMs: Long = 15 * 60_000L, private val threshold: Int = 3) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun record(nowMs: Long) {
        times.addLast(nowMs)
        while (times.size > 20) times.removeFirst()
    }

    @Synchronized
    fun struggling(nowMs: Long): Boolean = times.count { nowMs - it in 0..windowMs } >= threshold
}

object StallStats {
    val log = StallLog()
}
