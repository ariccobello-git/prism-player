package dev.red74.prismplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R

/**
 * "Close the app?" shown when back is pressed on the home screen. Focus starts on "No". "Yes" (or [closeNow], when
 * the app closes for inactivity) starts the closing animation ([BlackHoleClosing]), and [onConfirm] runs at its end.
 */
@Composable
fun ExitScreen(onConfirm: () -> Unit, onDismiss: () -> Unit, closeNow: Boolean = false) {
    var closing by remember { mutableStateOf(closeNow) }
    // Once closing has started there is no way back.
    BackHandler(enabled = true) { if (!closing) onDismiss() }

    if (closing) {
        BlackHoleClosing(onFinished = onConfirm)
        return
    }

    val noFocus = remember { FocusRequester() }
    RequestFocusOnEnter(noFocus)
    PrismBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.widthIn(max = 720.dp)) {
                Column(
                    Modifier.padding(48.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrismText(
                        text = stringResource(R.string.exit_title),
                        size = 36.sp,
                        weight = FontWeight.Bold,
                    )
                    PrismText(
                        text = stringResource(R.string.exit_message),
                        size = 20.sp,
                        color = PrismColors.TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        GlassButton(
                            text = stringResource(R.string.action_no),
                            onClick = onDismiss,
                            focusRequester = noFocus,
                            emphasized = true,
                        )
                        GlassButton(
                            text = stringResource(R.string.action_yes),
                            onClick = { closing = true },
                        )
                    }
                }
            }
        }
    }
}
