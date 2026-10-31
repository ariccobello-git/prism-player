package dev.red74.prismplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.red74.prismplayer.data.IprismLink
import dev.red74.prismplayer.data.SharedLine
import dev.red74.prismplayer.pairing.LocalNetwork
import dev.red74.prismplayer.pairing.PairingServer
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairUiState {
    data object Starting : PairUiState

    data class Showing(
        val qrContent: String,
        val codeDisplay: String,
        val address: String,
        val remainingSeconds: Int,
    ) : PairUiState

    data object Expired : PairUiState
    data object Done : PairUiState
    data object NoNetwork : PairUiState
    data object Failed : PairUiState
}

/**
 * Runs one pairing attempt at a time. The HTTP server exists only while a code is live:
 * it is stopped on expiry, after a successful transfer, when the screen is left and when this model is cleared.
 */
class PairViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as PrismApp).container
    private val manager = container.pairingManager

    private val _state = MutableStateFlow<PairUiState>(PairUiState.Starting)
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    private var server: PairingServer? = null
    private var ticker: Job? = null

    // Set on the server thread as soon as a playlist is accepted, so the ticker cannot mistake the
    // burned code for an expired one and shut the server down before the phone gets its answer.
    @Volatile
    private var received = false

    /** Starts (or restarts) pairing. Any earlier code is invalidated by [PairingManager.newSession]. */
    fun start() {
        stopServer()
        ticker?.cancel()
        received = false
        _state.value = PairUiState.Starting

        val ip = LocalNetwork.localIpv4()
        if (ip == null) {
            _state.value = PairUiState.NoNetwork
            return
        }

        val candidate = PairingServer(
            manager,
            onPlaylist = ::onPlaylistReceived,
            isDuplicate = { container.playlistStore.contains(it) },
            onIprismLink = ::onIprismLinkReceived,
        )
        try {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: IOException) {
            _state.value = PairUiState.Failed
            return
        }
        server = candidate

        val address = "http://$ip:${candidate.listeningPort}"
        manager.newSession()
        ticker = viewModelScope.launch {
            while (isActive && !received) {
                val session = manager.current()
                val remaining = manager.remainingMs()
                if (session == null || remaining <= 0L) {
                    _state.value = PairUiState.Expired
                    stopServer()
                    break
                }
                _state.value = PairUiState.Showing(
                    qrContent = "$address/pair?c=${session.code}",
                    codeDisplay = session.display,
                    address = address,
                    remainingSeconds = ((remaining + 999) / 1000).toInt(),
                )
                delay(500)
            }
        }
    }

    /** Called when the pairing screen is left. */
    fun stop() {
        ticker?.cancel()
        manager.invalidate()
        stopServer()
    }

    // Runs on a server thread; hop to the main scope before touching state.
    private fun onPlaylistReceived(name: String, url: String) {
        received = true
        viewModelScope.launch {
            ticker?.cancel()
            _state.value = try {
                container.playlistStore.add(name, url)
                PairUiState.Done
            } catch (e: Exception) {
                PairUiState.Failed
            }
            // The code is already burned, so the server is inert; give it a moment to finish
            // sending the confirmation page to the phone before closing connections.
            delay(1_500)
            stopServer()
        }
    }

    private fun onIprismLinkReceived(link: IprismLink) {
        received = true
        viewModelScope.launch {
            ticker?.cancel()
            _state.value = try {
                withContext(Dispatchers.IO) { container.iprismLink.save(link) }
                SharedLine.setLink(link)
                PairUiState.Done
            } catch (e: Exception) {
                PairUiState.Failed
            }
            delay(1_500)
            stopServer()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stop()
    }
}
