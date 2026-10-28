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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.ui.ChannelsScreen
import dev.red74.prismplayer.ui.ExitScreen
import dev.red74.prismplayer.ui.HomeScreen
import dev.red74.prismplayer.ui.ImportScreen
import dev.red74.prismplayer.ui.PairScreen
import dev.red74.prismplayer.ui.PlayerScreen
import dev.red74.prismplayer.ui.SplashScreen

private sealed interface Screen {
    data object Home : Screen
    data object Import : Screen
    data object Pair : Screen
    data object Exit : Screen
    data class Channels(val playlistId: String) : Screen
    data class Player(val startIndex: Int) : Screen
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
                (fadeOut(tween(450)) + scaleOut(tween(450), targetScale = 0.96f))
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
        forward = true
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) {
            forward = false
            stack.removeAt(stack.lastIndex)
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    // Last "back" on the home screen: ask before leaving.
    BackHandler(enabled = stack.size == 1 && !confirmExit) {
        forward = true
        confirmExit = true
    }

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val channelsState by viewModel.channels.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val aspect by viewModel.aspect.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()

    val current: Screen = if (confirmExit) Screen.Exit else stack.last()

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
        when (screen) {
            Screen.Home -> HomeScreen(
                playlists = playlists,
                verifying = verifying,
                notice = notice,
                onOpen = { id -> push(Screen.Channels(id)) },
                onVerify = viewModel::verifyPlaylist,
                onVerifyAll = viewModel::verifyAll,
                onDelete = viewModel::removePlaylist,
                onAddUrl = { push(Screen.Import) },
                onPair = { push(Screen.Pair) },
                onNoticeShown = viewModel::clearNotice,
            )

            Screen.Exit -> ExitScreen(
                onConfirm = { activity?.finish() },
                onDismiss = {
                    forward = false
                    confirmExit = false
                },
            )

            Screen.Import -> ImportScreen(
                onSubmit = { name, url ->
                    viewModel.importPlaylist(name, url).also { result ->
                        if (result is PlaylistUrl.Result.Valid) pop()
                    }
                },
                onBack = ::pop,
            )

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

            is Screen.Channels -> {
                LaunchedEffect(screen.playlistId) { viewModel.openPlaylist(screen.playlistId) }
                ChannelsScreen(
                    state = channelsState,
                    onPlay = { index -> push(Screen.Player(index)) },
                    onRetry = viewModel::reloadPlaylist,
                    onBack = ::pop,
                )
            }

            is Screen.Player -> {
                if (channelsState.channels.isEmpty()) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    PlayerScreen(
                        channels = channelsState.channels,
                        startIndex = screen.startIndex,
                        aspect = aspect,
                        onAspectChange = viewModel::setAspect,
                        onIndexChange = viewModel::setLastIndex,
                        onExit = ::pop,
                    )
                }
            }
        }
    }
}
