package dev.red74.prismplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.FetchException
import dev.red74.prismplayer.data.M3uParser
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.data.SavedPlaylist
import dev.red74.prismplayer.player.AspectMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Notice { ADDED, REMOVED, PAIRED, SAVE_FAILED, VERIFY_OK, VERIFY_FAILED, VERIFY_ALL_OK, VERIFY_SOME_FAILED }

enum class LoadError { NETWORK, HTTP, TOO_LARGE, EMPTY, UNKNOWN }

data class ChannelsState(
    val playlistId: String? = null,
    val playlistName: String = "",
    val loading: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val error: LoadError? = null,
    val httpCode: Int? = null,
    val lastIndex: Int = 0,
    /** The list had more entries than the app keeps in memory; only the first ones are shown. */
    val truncated: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as PrismApp).container
    private val store = container.playlistStore

    val playlists: StateFlow<List<SavedPlaylist>> = store.items

    private val _channels = MutableStateFlow(ChannelsState())
    val channels: StateFlow<ChannelsState> = _channels.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _aspect = MutableStateFlow(container.settings.aspectMode)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** True once the saved playlists have been read from storage. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { store.load() }
            _ready.value = true
        }
    }

    /** Validates synchronously (the caller shows the error) and saves in the background when valid. */
    fun importPlaylist(name: String, rawUrl: String): PlaylistUrl.Result {
        val result = PlaylistUrl.validate(rawUrl)
        if (result is PlaylistUrl.Result.Valid) {
            val label = name.filter { !it.isISOControl() }.trim().take(60).ifBlank { result.host }
            viewModelScope.launch {
                _notice.value = runCatching { store.add(label, result.url) }
                    .fold(onSuccess = { Notice.ADDED }, onFailure = { Notice.SAVE_FAILED })
            }
        }
        return result
    }

    fun removePlaylist(id: String) {
        viewModelScope.launch {
            _notice.value = runCatching { store.remove(id) }
                .fold(onSuccess = { Notice.REMOVED }, onFailure = { Notice.SAVE_FAILED })
            if (_channels.value.playlistId == id) {
                _channels.value = ChannelsState()
            }
        }
    }

    fun openPlaylist(id: String, force: Boolean = false) {
        val current = _channels.value
        if (!force && current.playlistId == id && current.channels.isNotEmpty()) return
        val playlist = store.find(id) ?: return

        loadJob?.cancel()
        _channels.value = ChannelsState(playlistId = id, playlistName = playlist.name, loading = true)
        loadJob = viewModelScope.launch {
            val result = loadPlaylist(playlist, keepChannels = true)
            val parsed = result.getOrNull()
            if (parsed == null) {
                val failure = result.exceptionOrNull() as? FetchException
                _channels.update {
                    it.copy(
                        loading = false,
                        error = when (failure?.kind) {
                            FetchException.Kind.HTTP -> LoadError.HTTP
                            FetchException.Kind.TOO_LARGE -> LoadError.TOO_LARGE
                            FetchException.Kind.NETWORK, FetchException.Kind.REDIRECT -> LoadError.NETWORK
                            null -> LoadError.UNKNOWN
                        },
                        httpCode = failure?.httpCode,
                    )
                }
                recordStatus(id, PlaylistStatus.FAILED, 0)
                return@launch
            }
            if (parsed.count == 0) {
                _channels.update { it.copy(loading = false, error = LoadError.EMPTY) }
                recordStatus(id, PlaylistStatus.FAILED, 0)
            } else {
                _channels.update {
                    it.copy(loading = false, channels = parsed.channels, error = null, truncated = parsed.truncated)
                }
                recordStatus(id, PlaylistStatus.OK, parsed.count)
            }
        }
    }

    private suspend fun loadPlaylist(playlist: SavedPlaylist, keepChannels: Boolean): Result<M3uParser.Result> =
        container.fetcher.fetch(playlist.url) { reader -> M3uParser.parse(reader, playlist.url, keepChannels) }

    private suspend fun recordStatus(id: String, status: PlaylistStatus, count: Int) {
        runCatching { store.updateStatus(id, status, count) }
    }

    /** Playlists being checked right now (the UI shows a spinner label on them). */
    private val _verifying = MutableStateFlow<Set<String>>(emptySet())
    val verifying: StateFlow<Set<String>> = _verifying.asStateFlow()

    /**
     * Downloads the list and counts its entries without keeping them, then stores the verdict.
     * Saving a playlist never does this by itself: the user starts it (for example after turning the VPN on).
     */
    fun verifyPlaylist(id: String) {
        val playlist = store.find(id) ?: return
        if (id in _verifying.value) return
        _verifying.update { it + id }
        viewModelScope.launch {
            val ok = checkOne(playlist)
            _verifying.update { it - id }
            _notice.value = if (ok) Notice.VERIFY_OK else Notice.VERIFY_FAILED
        }
    }

    fun verifyAll() {
        val targets = store.items.value.filter { it.id !in _verifying.value }
        if (targets.isEmpty()) return
        _verifying.update { it + targets.map { target -> target.id } }
        viewModelScope.launch {
            var failed = 0
            for (target in targets) {
                if (!checkOne(target)) failed++
                _verifying.update { it - target.id }
            }
            _notice.value = if (failed == 0) Notice.VERIFY_ALL_OK else Notice.VERIFY_SOME_FAILED
        }
    }

    private suspend fun checkOne(playlist: SavedPlaylist): Boolean {
        val parsed = loadPlaylist(playlist, keepChannels = false).getOrNull()
        val ok = parsed != null && parsed.count > 0
        recordStatus(playlist.id, if (ok) PlaylistStatus.OK else PlaylistStatus.FAILED, parsed?.count ?: 0)
        return ok
    }

    fun reloadPlaylist() {
        _channels.value.playlistId?.let { openPlaylist(it, force = true) }
    }

    fun setLastIndex(index: Int) {
        _channels.update { it.copy(lastIndex = index) }
    }

    fun setAspect(mode: AspectMode) {
        _aspect.value = mode
        container.settings.aspectMode = mode
    }

    fun postNotice(notice: Notice) {
        _notice.value = notice
    }

    fun clearNotice() {
        _notice.value = null
    }
}
