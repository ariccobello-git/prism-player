package dev.red74.prismplayer

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.red74.prismplayer.data.PlaylistUrl
import dev.red74.prismplayer.ui.ChannelsScreen
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

/** Tiny back-stack navigation: only the top screen is composed. */
@Composable
fun PrismRoot(
    appViewModel: AppViewModel = viewModel(),
    pairViewModel: PairViewModel = viewModel(),
) {
    val viewModel = appViewModel
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val channelsState by viewModel.channels.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val aspect by viewModel.aspect.collectAsStateWithLifecycle()

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
