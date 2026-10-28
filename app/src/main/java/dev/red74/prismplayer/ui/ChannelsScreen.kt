package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.ChannelsState
import dev.red74.prismplayer.LoadError
import dev.red74.prismplayer.R

@Composable
fun ChannelsScreen(
    state: ChannelsState,
    onPlay: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.lastIndex.coerceIn(0, (state.channels.size - 1).coerceAtLeast(0)),
    )
    val itemFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    // Land on the channel that was playing last, or on "retry" when loading failed.
    RequestFocusOnEnter(itemFocus, key = state.channels.isNotEmpty())
    RequestFocusOnEnter(retryFocus, key = state.error)

    PrismBackground {
        GlassPanel(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                    PrismText(
                        text = state.playlistName,
                        size = 34.sp,
                        weight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                when {
                    state.loading -> PrismText(
                        text = stringResource(R.string.channels_loading),
                        size = 22.sp,
                        color = PrismColors.TextSecondary,
                    )

                    state.error != null -> {
                        PrismText(
                            text = errorText(state.error, state.httpCode),
                            size = 22.sp,
                            color = PrismColors.Danger,
                        )
                        GlassButton(
                            text = stringResource(R.string.channels_retry),
                            onClick = onRetry,
                            focusRequester = retryFocus,
                            emphasized = true,
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(state.channels, key = { index, _ -> index }) { index, channel ->
                            val target = state.lastIndex.coerceIn(0, state.channels.lastIndex)
                            GlassButton(
                                text = channel.name,
                                subtitle = channel.group,
                                onClick = { onPlay(index) },
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = if (index == target) itemFocus else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun errorText(error: LoadError, httpCode: Int?): String = when (error) {
    LoadError.NETWORK -> stringResource(R.string.load_error_network)
    LoadError.HTTP -> stringResource(R.string.load_error_http, httpCode ?: 0)
    LoadError.TOO_LARGE -> stringResource(R.string.load_error_too_large)
    LoadError.EMPTY -> stringResource(R.string.load_error_empty)
    LoadError.UNKNOWN -> stringResource(R.string.load_error_unknown)
}
