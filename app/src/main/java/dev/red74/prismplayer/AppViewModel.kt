package dev.red74.prismplayer

import android.app.Application
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.red74.prismplayer.data.AppUpdater
import dev.red74.prismplayer.data.Availability
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.CatalogBuilder
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.EpgUrl
import dev.red74.prismplayer.data.FavoriteKey
import dev.red74.prismplayer.data.FavoriteList
import dev.red74.prismplayer.data.FetchException
import dev.red74.prismplayer.data.HttpAgent
import dev.red74.prismplayer.data.M3uParser
import dev.red74.prismplayer.data.PlaylistStatus
import dev.red74.prismplayer.data.ProviderHosts
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.data.PlaylistStore
import dev.red74.prismplayer.data.SavedPlaylist
import dev.red74.prismplayer.data.UpdateCheck
import dev.red74.prismplayer.data.XmltvParser
import dev.red74.prismplayer.player.AspectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

enum class Notice {
    ADDED, REMOVED, PAIRED, SAVE_FAILED, DUPLICATE,
    VERIFY_OK, VERIFY_FAILED, VERIFY_ALL_OK, VERIFY_SOME_FAILED,
    UPDATE_UP_TO_DATE,
}

/**
 * The video and audio engine (Media3) built into the app and the newest stable one published. The engine cannot be
 * replaced on its own: a newer one reaches the TV inside the next release of the app (see .github/workflows/media3.yml).
 */
data class EngineInfo(val installed: String, val latest: String?) {
    val newer: Boolean get() = latest != null && UpdateCheck.compare(latest, installed) > 0
}

/** Where the app update stands; the update page shows it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val installed: String, val engine: EngineInfo? = null) : UpdateState
    data object Offline : UpdateState
    data class Available(
        val release: UpdateCheck.AppRelease,
        val installed: String,
        val engine: EngineInfo? = null,
    ) : UpdateState
    /** [percent] is -1 while the size is not known yet. */
    data class Downloading(val release: UpdateCheck.AppRelease, val percent: Int) : UpdateState
    /** Android's installer was opened; if the user closed it, "Update" opens it again with the same file. */
    data class InstallerOpened(val release: UpdateCheck.AppRelease) : UpdateState
    data class Failed(val release: UpdateCheck.AppRelease?, val problem: AppUpdater.Problem) : UpdateState
}

enum class LoadError { NETWORK, HTTP, EMPTY, UNKNOWN, TIMEOUT, DNS, REFUSED, TLS, NOT_A_PLAYLIST }

/** The opened playlist, organised as Live / Movies / Series. */
data class LibraryState(
    val playlistId: String? = null,
    val playlistName: String = "",
    val loading: Boolean = false,
    val error: LoadError? = null,
    val httpCode: Int? = null,
    /** Entries read so far while the list is loading, so a long load shows that it is moving. */
    val loadedCount: Int = 0,
    val catalog: Catalog = Catalog.Empty,
)

enum class LibraryTab { LIVE, MOVIES, SERIES, FAVORITES }

/** Where the user was in the library, kept here so coming back from the player restores it. */
data class LibraryNav(
    val tab: LibraryTab = LibraryTab.LIVE,
    val category: Int = 0,
    /**
     * Where the cursor lives in the group levels of Live, Movies and Series: 0 the main groups, 1 the sub-groups,
     * 2 the contents. Back goes up one level at a time.
     */
    val level: Int = 0,
    /** Selected sub-group of the main group: 0 is "all", n is the n-th sub-group. */
    val sub: Int = 0,
    val sort: dev.red74.prismplayer.data.SortMode = dev.red74.prismplayer.data.SortMode.STANDARD,
    val query: String = "",
    val showTitle: String? = null,
    val showGroup: String? = null,
    val movieUrl: String? = null,
    /** The episode whose page is open inside [showTitle] (kept in memory only). */
    val episodeUrl: String? = null,
    val season: Int = -1,
    val itemIndex: Int = 0,
    /** True right after starting playback, so coming back puts the cursor on the item that was played. */
    val resume: Boolean = false,
    /** Address of the last content played (kept in memory only), so coming back puts the cursor on it. */
    val played: String? = null,
    /**
     * Where a detail page (film, series) was opened from when that is not its own group list: Favourites or a
     * search. Back from the detail returns exactly there instead of going up the groups of the other tab.
     */
    val origin: LibraryNav? = null,
    /** Favourite key of the series to put the cursor on when going back to Favourites. */
    val returnKey: String? = null,
)

/** What the full-screen player plays: a list (a category, a season) and where to start in it. */
data class PlayQueue(val items: List<Channel> = emptyList(), val startIndex: Int = 0, val follow: Boolean = false)

/** A saved copy younger than this opens without touching the network. */
private const val COPY_FRESH_MS = 6L * 60 * 60 * 1000

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

    private val _favoriteList = MutableStateFlow<FavoriteList?>(null)
    /** The favourites found in the open list (null while the first search runs), ready before the page opens. */
    val favoriteList: StateFlow<FavoriteList?> = _favoriteList.asStateFlow()

    private val _unavailable = MutableStateFlow(Availability.live(container.settings.unavailable, System.currentTimeMillis()))
    /** Keys (see [FavoriteKey]) of the contents the provider could not deliver lately. */
    val unavailable: StateFlow<Map<String, Long>> = _unavailable.asStateFlow()
    private var lastGoodPlaybackMs = 0L

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
    /** The quiet download of an old copy (see [refreshInBackground]); it stops when another list opens. */
    private var refreshJob: Job? = null

    private val updater = AppUpdater(application)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateJob: Job? = null
    /** The automatic check is tried at most once per start of the app, even when GitHub cannot be reached. */
    private var autoCheckTried = false

    /** Catalogs read from the list database that may still be on screen; closed once another list is opened. */
    private val openCatalogs = ArrayList<Catalog>()

    init {
        viewModelScope.launch {
            runCatching { store.load() }
            _ready.value = true
        }
        // The favourites are looked up in the background as soon as a list is ready and after every change; a
        // newer change cancels a search still running. The page then only shows what is already here.
        viewModelScope.launch {
            combine(_library.map { it.catalog }.distinctUntilChanged(), _favorites) { catalog, keys -> catalog to keys }
                .collectLatest { (catalog, keys) ->
                    if (catalog.isEmpty) {
                        _favoriteList.value = null
                        return@collectLatest
                    }
                    try {
                        _favoriteList.value = withContext(Dispatchers.IO) { FavoriteList.find(catalog, keys) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A catalog closed meanwhile (another list opened) simply gives no result.
                    }
                }
        }
        viewModelScope.launch(Dispatchers.IO) { container.catalogStore.cleanUp() }
    }

    override fun onCleared() {
        openCatalogs.forEach { it.close() }
        openCatalogs.clear()
    }

    /** Closes the catalogs of lists no longer shown, a little later, once the screen has stopped drawing their rows. */
    private fun retireCatalogs() {
        if (openCatalogs.isEmpty()) return
        val old = openCatalogs.toList()
        openCatalogs.clear()
        viewModelScope.launch {
            delay(RETIRE_DELAY_MS)
            old.forEach { it.close() }
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
            withContext(Dispatchers.IO) { container.catalogStore.delete(id) }
            _notice.value = runCatching { store.remove(id) }
                .fold(onSuccess = { Notice.REMOVED }, onFailure = { Notice.SAVE_FAILED })
            if (_library.value.playlistId == id) {
                loadJob?.cancel()
                epgJob?.cancel()
                refreshJob?.cancel()
                _library.value = LibraryState()
                _epg.value = EpgData.Empty
                retireCatalogs()
            }
        }
    }

    // --- loading, organising, verifying ---------------------------------------------------------------------

    private class Loaded(val parsed: M3uParser.Result?, val error: LoadError?, val httpCode: Int?)

    private suspend fun load(
        playlist: SavedPlaylist,
        keepChannels: Boolean,
        onProgress: ((Int) -> Unit)? = null,
    ): Loaded {
        // Some panels refuse one identity or answer a web page to it: try the next identity for those cases only.
        // If every identity is refused (HTTP error), the same request is retried in other common spellings.
        val first = tryAgents(playlist, playlist.url, keepChannels, onProgress)
        if (first.error != LoadError.HTTP) return first
        var last = first
        for (alt in PlaylistUrl.alternatives(playlist.url)) {
            val again = tryAgents(playlist, alt, keepChannels, onProgress, quick = true)
            if (again.error == null) return again
            if (again.error != LoadError.HTTP) last = again
        }
        return last
    }

    private suspend fun tryAgents(
        playlist: SavedPlaylist,
        url: String,
        keepChannels: Boolean,
        onProgress: ((Int) -> Unit)?,
        quick: Boolean = false,
    ): Loaded {
        var last = Loaded(null, LoadError.UNKNOWN, null)
        val agents = if (quick) HttpAgent.LIST_AGENTS.take(3) else HttpAgent.LIST_AGENTS
        for (agent in agents) {
            val result = container.fetcher.fetch(url, listOf(agent)) { reader ->
                M3uParser.parse(reader, playlist.url, keepChannels, onProgress)
            }
            val parsed = result.getOrNull()
            if (parsed == null) {
                val failure = result.exceptionOrNull() as? FetchException
                last = Loaded(null, failure?.kind.toLoadError(), failure?.httpCode)
                if (failure?.kind == FetchException.Kind.HTTP) continue
                return last
            }
            if (parsed.count == 0) {
                last = Loaded(parsed, if (parsed.sawPlaylistMarker) LoadError.EMPTY else LoadError.NOT_A_PLAYLIST, null)
                if (!parsed.sawPlaylistMarker) continue
                return last
            }
            return Loaded(parsed, null, null)
        }
        return last
    }

    private fun FetchException.Kind?.toLoadError(): LoadError = when (this) {
        FetchException.Kind.HTTP -> LoadError.HTTP
        FetchException.Kind.TIMEOUT -> LoadError.TIMEOUT
        FetchException.Kind.DNS -> LoadError.DNS
        FetchException.Kind.REFUSED -> LoadError.REFUSED
        FetchException.Kind.TLS -> LoadError.TLS
        FetchException.Kind.NETWORK, FetchException.Kind.REDIRECT -> LoadError.NETWORK
        null -> LoadError.UNKNOWN
    }

    fun openPlaylist(id: String, force: Boolean = false) {
        val current = _library.value
        if (!force && current.playlistId == id && !current.catalog.isEmpty) {
            // The same list again: the cursor goes back to where it was in it.
            _nav.update { it.copy(resume = true) }
            return
        }
        if (!force && current.playlistId == id && current.loading && loadJob?.isActive == true) {
            // Back and in again while the list is still downloading: the download goes on, it never starts over.
            return
        }
        val playlist = store.find(id) ?: return
        ProviderHosts.noteUrl(playlist.url)

        if (current.playlistId != id) _nav.value = LibraryNav()
        loadJob?.cancel()
        epgJob?.cancel()
        // The old list's quiet refresh would keep the one line to the panel busy while this list waits for it.
        refreshJob?.cancel()
        _epg.value = EpgData.Empty
        _library.value = LibraryState(playlistId = id, playlistName = playlist.name, loading = true)
        retireCatalogs()
        loadJob = viewModelScope.launch {
            // The list database opens at once (only group names and counts are read); the network is used only
            // to refresh it when it is old (or on request).
            if (!force) {
                val stored = withContext(Dispatchers.IO) { container.catalogStore.open(id) }
                if (stored != null) {
                    openCatalogs += stored
                    _library.update { it.copy(loading = false, error = null, catalog = stored) }
                    loadGuide(playlist, stored)
                    val age = container.catalogStore.ageMs(id)
                    if (stored.outdated || (age != null && age > COPY_FRESH_MS)) refreshInBackground(playlist, stored.totalEntries, swap = stored.outdated)
                    return@launch
                }
            }

            val loaded = load(playlist, keepChannels = true) { count ->
                _library.update { if (it.playlistId == id && it.loading) it.copy(loadedCount = count) else it }
            }
            val parsed = loaded.parsed
            if (loaded.error != null || parsed == null) {
                _library.update { it.copy(loading = false, error = loaded.error ?: LoadError.UNKNOWN, httpCode = loaded.httpCode) }
                recordStatus(id, PlaylistStatus.FAILED, 0, loaded.error, loaded.httpCode)
                return@launch
            }
            val catalog = buildCatalog(parsed)
            _library.update { it.copy(loading = false, error = null, catalog = catalog) }
            recordStatus(id, PlaylistStatus.OK, parsed.count, null, null)
            loadGuide(playlist, catalog)
            saveDatabase(id, catalog)
        }
    }

    private suspend fun buildCatalog(parsed: M3uParser.Result): Catalog = withContext(Dispatchers.Default) {
        CatalogBuilder.build(parsed.channels, parsed.epgUrl, parsed.truncated)
    }

    /** Stores the list in its database for the next opening; a failure just means the next opening downloads it again. */
    private suspend fun saveDatabase(id: String, catalog: Catalog) = withContext(Dispatchers.IO) {
        runCatching { container.catalogStore.write(id, catalog) }
    }

    /** The shown copy is old: download again quietly and swap it in only if the list really changed. */
    /** [swap]: show the renewed list even with the same number of entries (its names changed, see CatalogDb.outdated). */
    private fun refreshInBackground(playlist: SavedPlaylist, shownCount: Int, swap: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val loaded = load(playlist, keepChannels = true)
            val parsed = loaded.parsed ?: return@launch
            if (loaded.error != null || _library.value.playlistId != playlist.id) return@launch
            recordStatus(playlist.id, PlaylistStatus.OK, parsed.count, null, null)
            val catalog = buildCatalog(parsed)
            saveDatabase(playlist.id, catalog)
            if (swap || catalog.totalEntries != shownCount) {
                _library.update { if (it.playlistId == playlist.id) it.copy(catalog = catalog) else it }
            }
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
            val ids = HashSet<String>()
            val names = HashSet<String>()
            withContext(Dispatchers.IO) {
                for (channel in catalog.allLive()) {
                    channel.tvgId?.lowercase()?.let(ids::add)
                    EpgData.normalize(channel.tvgName)?.let(names::add)
                    EpgData.normalize(channel.name)?.let(names::add)
                }
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
        // A removal shows at once; an addition appears when the background search (a moment) is done.
        if (key in _favorites.value) _favoriteList.update { it?.without(key) }
        val updated = _favorites.value.let { if (key in it) it - key else it + key }
        _favorites.value = updated
        container.settings.favorites = updated
    }

    /** [follow]: the list is the one on screen, so zapping in the player moves the cursor there too. */
    fun play(items: List<Channel>, startIndex: Int, follow: Boolean = false) {
        val start = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        _queue.value = PlayQueue(items, start, follow)
        items.getOrNull(start)?.let { first -> _nav.update { it.copy(played = first.url) } }
    }

    fun setLastIndex(index: Int) {
        val queue = _queue.value
        val played = queue.items.getOrNull(index)?.url
        _nav.update {
            // An episode page follows the player when it goes on to the next episode by itself.
            val episode = if (it.episodeUrl != null && played != null) played else it.episodeUrl
            if (queue.follow) it.copy(itemIndex = index, played = played, episodeUrl = episode)
            else it.copy(played = played, episodeUrl = episode)
        }
    }

    /** A content started: its mark (if any) goes, and the network is proved to work. */
    fun playbackWorked(channel: Channel) {
        lastGoodPlaybackMs = System.currentTimeMillis()
        val key = FavoriteKey.of(channel)
        if (key in _unavailable.value) saveUnavailable(_unavailable.value - key)
    }

    /** A content failed: it is marked when the rules of [Availability] say the provider is at fault. */
    fun playbackFailed(channel: Channel, trouble: Availability.Trouble, afterRetry: Boolean, preview: Boolean) {
        val now = System.currentTimeMillis()
        val proven = lastGoodPlaybackMs > 0L && now - lastGoodPlaybackMs < Availability.NETWORK_PROOF_MS
        if (Availability.marks(trouble, afterRetry, proven, preview)) {
            saveUnavailable(Availability.live(_unavailable.value, now) + (FavoriteKey.of(channel) to now))
        }
    }

    private fun saveUnavailable(marks: Map<String, Long>) {
        _unavailable.value = marks
        container.settings.unavailable = marks
    }

    fun setAspect(mode: AspectMode) {
        _aspect.value = mode
        container.settings.aspectMode = mode
    }

    /**
     * The automatic check, once every 3 days: called a few seconds after the home page shows, so it never slows the
     * start. It reads only GitHub's list of releases. A newer release opens the update page (see PrismRoot); no news
     * shows a short "Prism is up to date" so the user knows the check happened; no connection stays silent and is
     * tried again at the next start.
     */
    fun autoCheckForUpdate() {
        if (autoCheckTried || updateJob?.isActive == true || _update.value != UpdateState.Idle) return
        autoCheckTried = true
        if (!UpdateCheck.autoCheckDue(container.settings.lastUpdateCheck, System.currentTimeMillis())) return
        updateJob = viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { updater.cleanUp() } }
            val json = UpdateCheck.fetchReleases() ?: return@launch
            container.settings.lastUpdateCheck = System.currentTimeMillis()
            val installed = BuildConfig.BUILD_LABEL
            val latest = UpdateCheck.latestAppRelease(json)
            if (latest != null && UpdateCheck.isNewer(latest.tag, installed)) {
                if (_update.value == UpdateState.Idle) _update.value = UpdateState.Available(latest, installed)
            } else if (_notice.value == null) {
                _notice.value = Notice.UPDATE_UP_TO_DATE
            }
        }
    }

    /** The "Check for updates" button: the update page shows the check and its result. */
    fun checkForUpdate() {
        updateJob?.cancel()
        _update.value = UpdateState.Checking
        updateJob = viewModelScope.launch {
            // The app's releases and the engine's latest version are read at the same time (GitHub and Google).
            val engineLatest = async { UpdateCheck.fetchLatestEngine() }
            val json = UpdateCheck.fetchReleases()
            val engine = EngineInfo(androidx.media3.common.MediaLibraryInfo.VERSION, engineLatest.await())
            if (json == null) {
                _update.value = UpdateState.Offline
                return@launch
            }
            container.settings.lastUpdateCheck = System.currentTimeMillis()
            val installed = BuildConfig.BUILD_LABEL
            val latest = UpdateCheck.latestAppRelease(json)
            _update.value = if (latest != null && UpdateCheck.isNewer(latest.tag, installed)) {
                UpdateState.Available(latest, installed, engine)
            } else {
                UpdateState.UpToDate(installed, engine)
            }
        }
    }

    /** "Update": downloads the release (or reuses the file already downloaded) and opens Android's installer. */
    fun installUpdate() {
        val release = when (val state = _update.value) {
            is UpdateState.Available -> state.release
            is UpdateState.InstallerOpened -> state.release
            is UpdateState.Failed -> state.release
            else -> null
        } ?: return
        val reuse = _update.value is UpdateState.InstallerOpened
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            try {
                val apk = if (reuse && updater.downloaded.isFile) {
                    updater.downloaded
                } else {
                    _update.value = UpdateState.Downloading(release, -1)
                    updater.download(release) { percent ->
                        _update.value = UpdateState.Downloading(release, percent)
                    }
                }
                updater.openInstaller(apk)
                _update.value = UpdateState.InstallerOpened(release)
            } catch (e: AppUpdater.Failure) {
                _update.value = UpdateState.Failed(release, e.problem)
            }
        }
    }

    /** "Later", "OK" or back on the update page: stops a download still running and forgets the result. */
    fun dismissUpdate() {
        updateJob?.cancel()
        updateJob = null
        _update.value = UpdateState.Idle
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
        const val RETIRE_DELAY_MS = 30_000L
    }
}
