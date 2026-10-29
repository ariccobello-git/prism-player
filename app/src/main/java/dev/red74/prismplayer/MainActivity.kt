package dev.red74.prismplayer

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.red74.prismplayer.ui.PrismTheme
import dev.red74.prismplayer.ui.UiSounds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While the app is in front the TV stick never goes to standby or starts its screensaver, whatever is on
        // screen (menus, a list, a preview or a full-screen stream), and the app itself never closes for inactivity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        UiSounds.init(this)
        setContent {
            PrismTheme {
                PrismRoot()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // One sound per press of a direction key; while the key stays down (auto-repeat) the interface is silent.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> UiSounds.play(UiSounds.Kind.MOVE)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        UiSounds.enabled = false
        super.onDestroy()
    }
}
