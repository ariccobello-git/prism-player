package dev.red74.prismplayer.device

import android.view.InputDevice
import android.view.KeyEvent

/**
 * The remote in the user's hand. Remotes differ a lot between models (some have Menu or media keys, many have
 * none), so the keys actually present are read from the input devices instead of guessed from the model name.
 */
data class DeviceProfile(
    val hasMenuKey: Boolean,
    val hasMediaKeys: Boolean,
) {
    /** True for the remote shape of the Xiaomi sticks: D-pad, OK, Back, Home and volume, no transport keys. */
    val minimalRemote: Boolean get() = !hasMediaKeys && !hasMenuKey

    companion object {
        @Volatile
        private var cached: DeviceProfile? = null

        fun get(): DeviceProfile = cached ?: detect().also { cached = it }

        private fun detect(): DeviceProfile {
            val menu = intArrayOf(KeyEvent.KEYCODE_MENU)
            val media = intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
            var hasMenu = false
            var hasMedia = false
            for (id in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(id) ?: continue
                if (device.isVirtual) continue
                val sources = device.sources
                val usable = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD ||
                    sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
                if (!usable) continue
                hasMenu = hasMenu || device.hasKeys(*menu)[0]
                hasMedia = hasMedia || device.hasKeys(*media).any { it }
            }
            return DeviceProfile(hasMenu, hasMedia)
        }
    }
}
