package dev.red74.prismplayer

import android.app.Application
import android.content.Context
import androidx.media3.common.util.Log as MediaLog
import dev.red74.prismplayer.data.AppSettings
import dev.red74.prismplayer.data.CatalogStore
import dev.red74.prismplayer.data.ContentInfo
import dev.red74.prismplayer.data.KeystoreKeyProvider
import dev.red74.prismplayer.data.ListCache
import dev.red74.prismplayer.data.PlaylistFetcher
import dev.red74.prismplayer.data.PlaylistStore
import dev.red74.prismplayer.data.SecureBox
import dev.red74.prismplayer.data.WatchProgress
import dev.red74.prismplayer.pairing.PairingManager
import java.io.File

class PrismApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The player's own messages can carry stream addresses (with the list's credentials): keep them out of
        // the system log, where other tools could read them. Nothing is formatted either, so it costs nothing.
        MediaLog.setLogLevel(MediaLog.LOG_LEVEL_OFF)
        container = AppContainer(this)
        WatchProgress.init(this)
        ContentInfo.init(this)
    }
}

/** Hand-wired dependencies; small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {
    private val keys = KeystoreKeyProvider()

    // noBackupFilesDir: excluded from backups and device-to-device transfer.
    val playlistStore = PlaylistStore(
        file = File(context.noBackupFilesDir, "playlists.bin"),
        box = SecureBox(keys::getOrCreateKey),
    )
    /** Last downloaded copy of each list (encrypted), so reopening a list does not wait for the network. */
    val listCache = ListCache(File(context.noBackupFilesDir, "lists"), keys::getOrCreateKey)
    /** The same lists as small databases, read a page at a time, so even a very big list opens at once. */
    val catalogStore = CatalogStore(File(context.noBackupFilesDir, "lists"), keys::getOrCreateKey)
    val fetcher = PlaylistFetcher()
    val pairingManager = PairingManager()
    val settings = AppSettings(context)
}
