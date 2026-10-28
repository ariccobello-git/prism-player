package dev.red74.prismplayer.device

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent

/**
 * What the app knows about the box it runs on and the remote in the user's hand. Remotes differ a lot between
 * models (some have Menu, Info or media keys, many have none), so the keys actually present are read from the
 * input devices instead of guessed from the model name.
 */
data class DeviceProfile(
    val isXiaomi: Boolean,
    val isTelevision: Boolean,
    val model: String,
    val hasMenuKey: Boolean,
    val hasInfoKey: Boolean,
    val hasMediaKeys: Boolean,
    val hasChannelKeys: Boolean,
) {
    /** True for the remote shape of the Xiaomi sticks: D-pad, OK, Back, Home and volume, no transport keys. */
    val minimalRemote: Boolean get() = !hasMediaKeys && !hasMenuKey

    companion object {
        @Volatile
        private var cached: DeviceProfile? = null

        fun get(context: Context): DeviceProfile =
            cached ?: detect(context).also { cached = it }

        /** Call again when a remote is connected or changed. */
        fun refresh(context: Context): DeviceProfile = detect(context).also { cached = it }

        private fun detect(context: Context): DeviceProfile {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val tv = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            val xiaomi = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("xiaomi", ignoreCase = true) ||
                Build.MODEL.startsWith("MiTV", ignoreCase = true) ||
                Build.MODEL.startsWith("Mi TV", ignoreCase = true)

            val menu = intArrayOf(KeyEvent.KEYCODE_MENU)
            val info = intArrayOf(KeyEvent.KEYCODE_INFO)
            val media = intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
            val channel = intArrayOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)

            var hasMenu = false
            var hasInfo = false
            var hasMedia = false
            var hasChannel = false
            for (id in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(id) ?: continue
                if (device.isVirtual) continue
                val sources = device.sources
                val usable = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD ||
                    sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
                if (!usable) continue
                hasMenu = hasMenu || device.hasKeys(*menu)[0]
                hasInfo = hasInfo || device.hasKeys(*info)[0]
                hasMedia = hasMedia || device.hasKeys(*media).any { it }
                hasChannel = hasChannel || device.hasKeys(*channel).any { it }
            }
            return DeviceProfile(xiaomi, tv, Build.MODEL.orEmpty(), hasMenu, hasInfo, hasMedia, hasChannel)
        }
    }
}
