package dev.red74.prismplayer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.red74.prismplayer.data.WatchNext
import dev.red74.prismplayer.ui.IdleExit
import dev.red74.prismplayer.ui.PrismTheme
import dev.red74.prismplayer.ui.UiSounds

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        // The language chosen on the Home page (flags); without a choice the TV's own language is used.
        super.attachBaseContext(dev.red74.prismplayer.ui.Appearance.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never two Prism Players on the device: the manifest keeps one task (singleTask), and if a launcher still
        // manages to create a second window this one closes and the open app comes to front instead.
        val running = current?.get()
        if (running != null && running !== this && !running.isFinishing && !running.isDestroyed) {
            finish()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        current = java.lang.ref.WeakReference(this)
        // While the app is in front the TV stick never goes to standby or starts its screensaver, whatever is on
        // screen (menus, a list, a preview or a full-screen stream). The app closes itself only when nobody uses it
        // and nothing is playing (see IdleExit).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        UiSounds.init(this)
        dev.red74.prismplayer.ui.Appearance.init(this)
        if (savedInstanceState == null) takeLink(intent)
        setContent {
            PrismTheme {
                PrismRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
    }

    /** A card of the "Play next" row of the TV home screen opens the film or series it names (see PrismRoot). */
    private fun takeLink(intent: Intent?) {
        WatchNext.parse(intent?.data)?.let { link.value = it }
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        IdleExit.touch()
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** Android 13 and later ask before notifications show: the first reminder asks once (a refusal only means no notification outside the app). */
    fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        IdleExit.touch()
        // A key pressed during the inactivity countdown only cancels it (its release is swallowed too).
        if (event.action == KeyEvent.ACTION_DOWN && IdleExit.countingDown) {
            IdleExit.countingDown = false
            swallowKeyUp = event.keyCode
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKeyUp) {
            swallowKeyUp = null
            return true
        }
        // One sound per press of a direction key; while the key stays down (auto-repeat) the interface is silent.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> UiSounds.play(UiSounds.Kind.MOVE)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private var swallowKeyUp: Int? = null

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
            UiSounds.enabled = false
        }
        super.onDestroy()
    }

    companion object {
        /** The "Play next" card that started the app and has not been opened yet. */
        val link = androidx.compose.runtime.mutableStateOf<WatchNext.Link?>(null)

        private var current: java.lang.ref.WeakReference<MainActivity>? = null

        /** True while the app is in front (reminders then show their banner inside it instead of a notification). */
        @Volatile var inFront: Boolean = false
            private set
    }
}
