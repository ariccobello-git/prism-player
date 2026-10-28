package dev.red74.prismplayer.data

import android.content.Context
import dev.red74.prismplayer.player.AspectMode

/** Non-sensitive preferences (currently the chosen video format). Playlist data never goes here. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("prism_settings", Context.MODE_PRIVATE)

    var aspectMode: AspectMode
        get() = AspectMode.fromName(prefs.getString(KEY_ASPECT, null))
        set(value) {
            prefs.edit().putString(KEY_ASPECT, value.name).apply()
        }

    /** Favourite keys (see [FavoriteKey]): hashes only, never playlist URLs. */
    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()
        set(value) {
            prefs.edit().putStringSet(KEY_FAVORITES, value.toSet()).apply()
        }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_ASPECT = "aspect_mode"
    }
}
