package dev.red74.prismplayer

import android.app.Application
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.EpgUrl
import dev.red74.prismplayer.data.FetchException
import dev.red74.prismplayer.data.M3uParser
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.PlaylistStore
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.data.SavedPlaylist
import dev.red74.prismplayer.data.XmltvParser
import dev.red74.prismplayer.player.AspectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

enum class Notice {
    ADDED, REMOVED, PAIRED, SAVE_FAILED, DUPLICATE,
    VERIFY_OK, VERIFY_FAILED, VERIFY_ALL_OK, VERIFY_SOME_FAILED,
}

enum class LoadError { NETWORK, HTTP, TOO_LARGE, EMPTY, UNKNOWN, TIMEOUT, DNS, REFUSED, TLS, NOT_A_PLAYLIST }

/** The opened playlist, organised as Live / Movies / Series. */
data class LibraryState(
    val playlistId: String? = null,
    val playlistName: String = "",
    val loading: Boolean = false,
    val error: LoadError? = null,
    val httpCode: Int? = null,
    val catalog: Catalog = Catalog.Empty,
)

enum class LibraryTab { LIVE, MOVIES, SERIES, FAVORITES }

/** Where the user was in the library, kept here so coming back from the player restores it. */
data class LibraryNav(
    val tab: LibraryTab = LibraryTab.LIVE,
    val category: Int = 0,
    val query: String = "",
    val showTitle: String? = null,
    val showGroup: String? = null,
    val movieUrl: String? = null,
    val season: Int = -1,
    val itemIndex: Int = 0,
    /** True right after starting playback, so coming back puts the cursor on the item that was played. */
    val resume: Boolean = false,
)

/** What the full-screen player plays: a list (a category, a season) and where to start in it. */
data class PlayQueue(val items: List<Channel> = emptyList(), val startIndex: Int = 0, val follow: Boolean = false)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as PrismApp).container
    private val store = container.playlistStore

    val playlists: StateFlow<List<SavedPlaylist>> = store.items

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _nav = MutableStateFlow(LibraryNav())
    val nav: StateFlow<LibraryNav> = _nav.asStateFlow()

    private val _queue = MutableStateFlow(PlayQueue())
    val queue: StateFlow<PlayQueue> = _queue.asStateFlow()

    private val _epg = MutableStateFlow(EpgData.Empty)
    val epg: StateFlow<EpgData> = _epg.asStateFlow()

    private val _favorites = MutableStateFlow(container.settings.favorites)
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _aspect = MutableStateFlow(container.settings.aspectMode)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** True once the saved playlists have been read from storage. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Playlists being checked right now (the UI shows a spinner label on them). */
    private val _verifying = MutableStateFlow<Set<String>>(emptySet())
    val verifying: StateFlow<Set<String>> = _verifying.asStateFlow()

    private var loadJob: Job? = null
    private var epgJob: Job? = null

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
            if (store.contains(result.url)) return PlaylistUrl.Result.Invalid(PlaylistUrl.Reason.DUPLICATE)
            val label = name.filter { !it.isISOControl() }.trim().take(60).ifBlank { result.host }
            viewModelScope.launch {
                _notice.value = runCatching { store.addIfNew(label, result.url) }.fold(
                    onSuccess = { if (it is PlaylistStore.AddResult.Added) Notice.ADDED else Notice.DUPLICATE },
                    onFailure = { Notice.SAVE_FAILED },
                )
            }
        }
        return result
    }

    fun removePlaylist(id: String) {
        viewModelScope.launch {
            _notice.value = runCatching { store.remove(id) }
                .fold(onSuccess = { Notice.REMOVED }, onFailure = { Notice.SAVE_FAILED })
            if (_library.value.playlistId == id) {
                loadJob?.cancel()
                epgJob?.cancel()
                _library.value = LibraryState()
                _epg.value = EpgData.Empty
            }
        }
    }

    // --- loading, organising, verifying ---------------------------------------------------------------------

    private class Loaded(val parsed: M3uParser.Result?, val error: LoadError?, val httpCode: Int?)

    private suspend fun load(playlist: SavedPlaylist, keepChannels: Boolean): Loaded {
        val result = container.fetcher.fetch(playlist.url) { reader ->
            M3uParser.parse(reader, playlist.url, keepChannels)
        }
        val parsed = result.getOrNull()
        if (parsed == null) {
            val failure = result.exceptionOrNull() as? FetchException
            return Loaded(null, failure?.kind.toLoadError(), failure?.httpCode)
        }
        if (parsed.count == 0) {
            return Loaded(parsed, if (parsed.sawPlaylistMarker) LoadError.EMPTY else LoadError.NOT_A_PLAYLIST, null)
        }
        return Loaded(parsed, null, null)
    }

    private fun FetchException.Kind?.toLoadError(): LoadError = when (this) {
        FetchException.Kind.HTTP -> LoadError.HTTP
        FetchException.Kind.TOO_LARGE -> LoadError.TOO_LARGE
        FetchException.Kind.TIMEOUT -> LoadError.TIMEOUT
        FetchException.Kind.DNS -> LoadError.DNS
        FetchException.Kind.REFUSED -> LoadError.REFUSED
        FetchException.Kind.TLS -> LoadError.TLS
        FetchException.Kind.NETWORK, FetchException.Kind.REDIRECT -> LoadError.NETWORK
        null -> LoadError.UNKNOWN
    }

    fun openPlaylist(id: String, force: Boolean = false) {
        val current = _library.value
        if (!force && current.playlistId == id && !current.catalog.isEmpty) return
        val playlist = store.find(id) ?: return

        if (current.playlistId != id) _nav.value = LibraryNav()
        loadJob?.cancel()
        epgJob?.cancel()
        _epg.value = EpgData.Empty
        _library.value = LibraryState(playlistId = id, playlistName = playlist.name, loading = true)
        loadJob = viewModelScope.launch {
            val loaded = load(playlist, keepChannels = true)
            val parsed = loaded.parsed
            if (loaded.error != null || parsed == null) {
                _library.update { it.copy(loading = false, error = loaded.error ?: LoadError.UNKNOWN, httpCode = loaded.httpCode) }
                recordStatus(id, PlaylistStatus.FAILED, 0, loaded.error, loaded.httpCode)
                return@launch
            }
            val catalog = withContext(Dispatchers.Default) {
                CatalogBuilder.build(parsed.channels, parsed.epgUrl, parsed.truncated)
            }
            _library.update { it.copy(loading = false, error = null, catalog = catalog) }
            recordStatus(id, PlaylistStatus.OK, parsed.count, null, null)
            loadGuide(playlist, catalog)
        }
    }

    fun reloadPlaylist() {
        _library.value.playlistId?.let { openPlaylist(it, force = true) }
    }

    /** Downloads the programme guide in the background; the library shows it as soon as it is ready. */
    private fun loadGuide(playlist: SavedPlaylist, catalog: Catalog) {
        val url = EpgUrl.derive(playlist.url, catalog.epgUrl) ?: return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val job = currentCoroutineContext()[Job]
            val live = catalog.live.flatMap { it.items }
            val ids = live.mapNotNullTo(HashSet()) { it.tvgId?.lowercase() }
            val names = HashSet<String>()
            for (channel in live) {
                EpgData.normalize(channel.tvgName)?.let(names::add)
                EpgData.normalize(channel.name)?.let(names::add)
            }
            val now = System.currentTimeMillis()
            val result = container.fetcher.fetchBytes(url) { input ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, null)
                XmltvParser.parse(
                    parser = parser,
                    wantedIds = ids,
                    wantedNames = names,
                    windowStartMs = now - GUIDE_PAST_MS,
                    windowEndMs = now + GUIDE_FUTURE_MS,
                    keepGoing = { job?.isActive != false },
                )
            }
            result.getOrNull()?.let { _epg.value = it }
        }
    }

    private suspend fun recordStatus(id: String, status: PlaylistStatus, count: Int, error: LoadError?, http: Int?) {
        runCatching { store.updateStatus(id, status, count, error?.name.orEmpty(), http ?: 0) }
    }

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
        val loaded = load(playlist, keepChannels = false)
        val ok = loaded.error == null
        recordStatus(
            playlist.id,
            if (ok) PlaylistStatus.OK else PlaylistStatus.FAILED,
            loaded.parsed?.count ?: 0,
            loaded.error,
            loaded.httpCode,
        )
        return ok
    }

    // --- library navigation, favourites, playback ------------------------------------------------------------

    fun updateNav(change: (LibraryNav) -> LibraryNav) {
        _nav.update(change)
    }

    fun toggleFavorite(key: String) {
        val updated = _favorites.value.let { if (key in it) it - key else it + key }
        _favorites.value = updated
        container.settings.favorites = updated
    }

    /** [follow]: the list is the one on screen, so zapping in the player moves the cursor there too. */
    fun play(items: List<Channel>, startIndex: Int, follow: Boolean = false) {
        _queue.value = PlayQueue(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), follow)
    }

    fun setLastIndex(index: Int) {
        if (_queue.value.follow) _nav.update { it.copy(itemIndex = index) }
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

    private companion object {
        const val GUIDE_PAST_MS = 3L * 60 * 60 * 1000
        const val GUIDE_FUTURE_MS = 36L * 60 * 60 * 1000
    }
}
