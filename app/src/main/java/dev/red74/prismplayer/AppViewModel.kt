package dev.red74.prismplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.FetchException
import dev.red74.prismplayer.data.M3uParser
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.data.SavedPlaylist
import dev.red74.prismplayer.player.AspectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Notice { ADDED, REMOVED, PAIRED, SAVE_FAILED }

enum class LoadError { NETWORK, HTTP, TOO_LARGE, EMPTY, UNKNOWN }

data class ChannelsState(
    val playlistId: String? = null,
    val playlistName: String = "",
    val loading: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val error: LoadError? = null,
    val httpCode: Int? = null,
    val lastIndex: Int = 0,
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

    private var loadJob: Job? = null

    init {
        viewModelScope.launch { runCatching { store.load() } }
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
            val result = container.fetcher.fetch(playlist.url)
            val text = result.getOrNull()
            if (text == null) {
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
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) { M3uParser.parse(text, playlist.url) }
            _channels.update {
                if (parsed.isEmpty()) {
                    it.copy(loading = false, error = LoadError.EMPTY)
                } else {
                    it.copy(loading = false, channels = parsed, error = null)
                }
            }
        }
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
