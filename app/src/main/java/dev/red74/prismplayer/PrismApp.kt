package dev.red74.prismplayer

import android.app.Application
import android.content.Context
import dev.red74.prismplayer.data.AppSettings
import dev.red74.prismplayer.data.KeystoreKeyProvider
import dev.red74.prismplayer.data.PlaylistFetcher
import dev.red74.prismplayer.data.PlaylistStore
import dev.red74.prismplayer.data.SecureBox
import dev.red74.prismplayer.pairing.PairingManager
import java.io.File

class PrismApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
    val fetcher = PlaylistFetcher()
    val pairingManager = PairingManager()
    val settings = AppSettings(context)
}
