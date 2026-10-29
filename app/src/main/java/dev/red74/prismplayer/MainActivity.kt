package dev.red74.prismplayer

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.red74.prismplayer.ui.PrismTheme
import dev.red74.prismplayer.ui.UiSounds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
