package dev.red74.prismplayer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.red74.prismplayer.player.PreviewPlayers
import dev.red74.prismplayer.ui.IdleCountdown
import dev.red74.prismplayer.ui.IdleExit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.ui.ExitScreen
import dev.red74.prismplayer.ui.HomeScreen
import dev.red74.prismplayer.ui.ImportScreen
import dev.red74.prismplayer.ui.LibraryScreen
import dev.red74.prismplayer.ui.PairScreen
import dev.red74.prismplayer.ui.PlayerScreen
import dev.red74.prismplayer.ui.ShortcutsScreen
import dev.red74.prismplayer.ui.SPLASH_EXIT_MS
import dev.red74.prismplayer.ui.SplashScreen
import dev.red74.prismplayer.ui.UpdatesScreen
import dev.red74.prismplayer.ui.UiSounds

private sealed interface Screen {
    data object Home : Screen
    data object Import : Screen
    data object Pair : Screen
    data object Updates : Screen
    data object Shortcuts : Screen
    data object Exit : Screen
    data class Library(val playlistId: String) : Screen
    data object Player : Screen
}

/** Opening animation first, then the app, joined by a soft zoom-and-fade. */
@Composable
fun PrismRoot(
    appViewModel: AppViewModel = viewModel(),
    pairViewModel: PairViewModel = viewModel(),
) {
    var splashDone by rememberSaveable { mutableStateOf(false) }
    AnimatedContent(
        targetState = splashDone,
        transitionSpec = {
            (fadeIn(tween(600)) + scaleIn(tween(600), initialScale = 1.06f)) togetherWith
                (fadeOut(tween(SPLASH_EXIT_MS)) + scaleOut(tween(SPLASH_EXIT_MS), targetScale = 0.96f))
        },
        label = "splashToApp",
    ) { done ->
        if (done) {
            PrismNavigation(appViewModel, pairViewModel)
        } else {
            val ready by appViewModel.ready.collectAsStateWithLifecycle()
            SplashScreen(ready = ready, onFinished = { splashDone = true })
        }
    }
}

/**
 * Tiny back-stack navigation. Moving forward slides the new screen in from the right with a soft spring while the
 * old one drifts left and dims; going back does the reverse, like a native iOS push and pop.
 */
@Composable
private fun PrismNavigation(
    appViewModel: AppViewModel,
    pairViewModel: PairViewModel,
) {
    val viewModel = appViewModel
    val activity = LocalContext.current as? Activity
    var confirmExit by remember { mutableStateOf(false) }
    var forward by remember { mutableStateOf(true) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    fun push(screen: Screen) {
        UiSounds.play(UiSounds.Kind.FORWARD)
        forward = true
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) {
            UiSounds.play(UiSounds.Kind.BACK)
            forward = false
            stack.removeAt(stack.lastIndex)
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    // Last "back" on the home screen: ask before leaving.
    BackHandler(enabled = stack.size == 1 && !confirmExit) {
        UiSounds.play(UiSounds.Kind.FORWARD)
        forward = true
        confirmExit = true
    }

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val epg by viewModel.epg.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val unavailable by viewModel.unavailable.collectAsStateWithLifecycle()
    val favoriteList by viewModel.favoriteList.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val aspect by viewModel.aspect.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()

    val current: Screen = if (confirmExit) Screen.Exit else stack.last()
    val focusMemory = remember { dev.red74.prismplayer.ui.FocusMemory() }

    // Interface sounds: everywhere except the player (and the exit screen switches them off itself).
    LaunchedEffect(current) { UiSounds.enabled = current !is Screen.Player }
    DisposableEffect(Unit) { onDispose { UiSounds.enabled = false } }
    LaunchedEffect(notice) {
        when (notice) {
            null -> Unit
            Notice.SAVE_FAILED, Notice.DUPLICATE, Notice.VERIFY_FAILED, Notice.VERIFY_SOME_FAILED -> UiSounds.play(UiSounds.Kind.ERROR)
            else -> UiSounds.play(UiSounds.Kind.SELECT)
        }
    }
    LaunchedEffect(library.error) { if (library.error != null) UiSounds.play(UiSounds.Kind.ERROR) }

    // Closing when nobody uses the app. The full-screen player (playing or paused), a preview on screen, a list
    // loading or being checked and the pairing page all count as "in use", so the countdown never shows over a
    // content being watched.
    val inUse by rememberUpdatedState(
        current is Screen.Player || current is Screen.Pair || library.loading || verifying.isNotEmpty(),
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var idleSeconds by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        IdleExit.touch()
        try {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (inUse || !resumed || PreviewPlayers.anyPlaying()) IdleExit.touch(now)
                val left = IdleExit.secondsLeft(now, IdleExit.lastActivityAt)
                if (left != null && idleSeconds == null) UiSounds.play(UiSounds.Kind.FORWARD)
                idleSeconds = left
                IdleExit.countingDown = left != null
                if (left == 0) {
                    UiSounds.enabled = false
                    activity?.finish()
                    break
                }
                kotlinx.coroutines.delay(200)
            }
        } finally {
            IdleExit.countingDown = false
        }
    }

    Box {
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val slide = spring<IntOffset>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
            if (forward) {
                (slideInHorizontally(slide) { it / 3 } + fadeIn(tween(320)) + scaleIn(tween(380), initialScale = 0.95f)) togetherWith
                    (slideOutHorizontally(slide) { -it / 5 } + fadeOut(tween(240)) + scaleOut(tween(380), targetScale = 0.97f))
            } else {
                (slideInHorizontally(slide) { -it / 5 } + fadeIn(tween(320)) + scaleIn(tween(380), initialScale = 0.97f)) togetherWith
                    (slideOutHorizontally(slide) { it / 3 } + fadeOut(tween(240)) + scaleOut(tween(380), targetScale = 0.95f))
            }
        },
        label = "screens",
    ) { screen ->
        // Each screen remembers where the cursor was (see FocusMemory), so coming back puts it there again.
        androidx.compose.runtime.CompositionLocalProvider(
            dev.red74.prismplayer.ui.LocalFocusMemory provides focusMemory,
            dev.red74.prismplayer.ui.LocalScreenKey provides screenKey(screen),
        ) {
        when (screen) {
            Screen.Home -> HomeScreen(
                playlists = playlists,
                verifying = verifying,
                notice = notice,
                onOpen = { id -> push(Screen.Library(id)) },
                onVerify = viewModel::verifyPlaylist,
                onVerifyAll = viewModel::verifyAll,
                onDelete = viewModel::removePlaylist,
                onAddUrl = { push(Screen.Import) },
                onPair = { push(Screen.Pair) },
                onUpdates = { push(Screen.Updates) },
                onShortcuts = { push(Screen.Shortcuts) },
                onNoticeShown = viewModel::clearNotice,
            )

            Screen.Exit -> ExitScreen(
                onConfirm = { activity?.finish() },
                onDismiss = {
                    UiSounds.play(UiSounds.Kind.BACK)
                    forward = false
                    confirmExit = false
                },
            )

            Screen.Import -> ImportScreen(
                onSubmit = { name, url ->
                    viewModel.importPlaylist(name, url).also { result ->
                        if (result is PlaylistUrl.Result.Valid) pop() else UiSounds.play(UiSounds.Kind.ERROR)
                    }
                },
                onBack = ::pop,
            )

            Screen.Updates -> UpdatesScreen(onBack = ::pop)

            Screen.Shortcuts -> ShortcutsScreen(onBack = ::pop)

            Screen.Pair -> {
                val pairState by pairViewModel.state.collectAsStateWithLifecycle()
                DisposableEffect(Unit) {
                    pairViewModel.start()
                    onDispose { pairViewModel.stop() }
                }
                LaunchedEffect(pairState) {
                    if (pairState is PairUiState.Done) viewModel.postNotice(Notice.PAIRED)
                }
                PairScreen(
                    state = pairState,
                    onNewCode = pairViewModel::start,
                    onClose = ::pop,
                )
            }

            is Screen.Library -> {
                LaunchedEffect(screen.playlistId) { viewModel.openPlaylist(screen.playlistId) }
                LibraryScreen(
                    state = library,
                    nav = nav,
                    epg = epg,
                    favorites = favorites,
                    onNav = viewModel::updateNav,
                    onPlay = { items, index, follow ->
                        viewModel.play(items, index, follow)
                        push(Screen.Player)
                    },
                    onToggleFavorite = { key ->
                        UiSounds.play(if (key in favorites) UiSounds.Kind.DESELECT else UiSounds.Kind.SELECT)
                        viewModel.toggleFavorite(key)
                    },
                    onRetry = viewModel::reloadPlaylist,
                    unavailable = unavailable.keys,
                    onPreviewWorked = viewModel::playbackWorked,
                    favoriteList = favoriteList,
                    onBack = ::pop,
                )
            }

            Screen.Player -> {
                if (queue.items.isEmpty()) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    PlayerScreen(
                        channels = queue.items,
                        startIndex = queue.startIndex,
                        aspect = aspect,
                        onAspectChange = viewModel::setAspect,
                        onIndexChange = viewModel::setLastIndex,
                        onExit = ::pop,
                        onPlaybackWorked = viewModel::playbackWorked,
                        onPlaybackFailed = { channel, trouble, afterRetry ->
                            viewModel.playbackFailed(channel, trouble, afterRetry, preview = false)
                        },
                    )
                }
            }
        }
        }
    }
    idleSeconds?.let { IdleCountdown(it) }
    }
}

/** One name per kind of screen, for [dev.red74.prismplayer.ui.FocusMemory]. */
private fun screenKey(screen: Screen): String = when (screen) {
    Screen.Home -> "home"
    Screen.Import -> "import"
    Screen.Pair -> "pair"
    Screen.Updates -> "updates"
    Screen.Shortcuts -> "shortcuts"
    Screen.Exit -> "exit"
    is Screen.Library -> "library"
    Screen.Player -> "player"
}
