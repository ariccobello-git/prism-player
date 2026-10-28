package dev.red74.prismplayer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.ui.ChannelsScreen
import dev.red74.prismplayer.ui.ExitScreen
import dev.red74.prismplayer.ui.SplashScreen
import dev.red74.prismplayer.ui.HomeScreen
import dev.red74.prismplayer.ui.ImportScreen
import dev.red74.prismplayer.ui.PairScreen
import dev.red74.prismplayer.ui.PlayerScreen

private sealed interface Screen {
    data object Home : Screen
    data object Import : Screen
    data object Pair : Screen
    data class Channels(val playlistId: String) : Screen
    data class Player(val startIndex: Int) : Screen
}

/** Opening animation first, then the app. */
@Composable
fun PrismRoot(
    appViewModel: AppViewModel = viewModel(),
    pairViewModel: PairViewModel = viewModel(),
) {
    var splashDone by rememberSaveable { mutableStateOf(false) }
    if (splashDone) {
        PrismNavigation(appViewModel, pairViewModel)
    } else {
        val ready by appViewModel.ready.collectAsStateWithLifecycle()
        SplashScreen(ready = ready, onFinished = { splashDone = true })
    }
}

/** Tiny back-stack navigation: only the top screen is composed. */
@Composable
private fun PrismNavigation(
    appViewModel: AppViewModel,
    pairViewModel: PairViewModel,
) {
    val viewModel = appViewModel
    val activity = LocalContext.current as? Activity
    var confirmExit by remember { mutableStateOf(false) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    // Last "back" on the home screen: ask before leaving.
    BackHandler(enabled = stack.size == 1 && !confirmExit) { confirmExit = true }

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val channelsState by viewModel.channels.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val aspect by viewModel.aspect.collectAsStateWithLifecycle()

    if (confirmExit) {
        ExitScreen(
            onConfirm = { activity?.finish() },
            onDismiss = { confirmExit = false },
        )
        return
    }

    when (val screen = stack.last()) {
        Screen.Home -> HomeScreen(
            playlists = playlists,
            notice = notice,
            onOpen = { id -> push(Screen.Channels(id)) },
            onDelete = viewModel::removePlaylist,
            onAddUrl = { push(Screen.Import) },
            onPair = { push(Screen.Pair) },
            onNoticeShown = viewModel::clearNotice,
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
