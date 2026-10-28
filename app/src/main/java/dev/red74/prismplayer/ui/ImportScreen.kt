package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.PlaylistUrl

/**
 * Manual import with the remote: D-pad to a field, OK to open the TV keyboard.
 * The typed URL lives only in `remember` (never in saved instance state).
 */
@Composable
fun ImportScreen(
    onSubmit: (name: String, url: String) -> PlaylistUrl.Result,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("http://") }
    var error by remember { mutableStateOf<PlaylistUrl.Reason?>(null) }
    val urlFocus = remember { FocusRequester() }
    RequestFocusOnEnter(urlFocus)

    fun submit() {
        val result = onSubmit(name, url)
        if (result is PlaylistUrl.Result.Invalid) error = result.reason
    }

    PrismBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(
                Modifier
                    .padding(48.dp)
                    .widthIn(max = 980.dp)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    PrismText(
                        text = stringResource(R.string.import_title),
                        size = 36.sp,
                        weight = FontWeight.Bold,
                    )
                    GlassTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            error = null
                        },
                        label = stringResource(R.string.import_url_label),
                        focusRequester = urlFocus,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    )
                    GlassTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.import_name_label),
                        imeAction = ImeAction.Done,
                        onDone = { submit() },
                    )
                    val reason = error
                    if (reason != null) {
                        PrismText(
                            text = reasonText(reason),
                            size = 20.sp,
                            color = PrismColors.Danger,
                        )
                    }
                    PrismText(
                        text = stringResource(R.string.import_hint),
                        size = 16.sp,
                        color = PrismColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassButton(
                            text = stringResource(R.string.action_save),
                            onClick = { submit() },
                            emphasized = true,
                        )
                        GlassButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onBack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun reasonText(reason: PlaylistUrl.Reason): String = stringResource(
    when (reason) {
        PlaylistUrl.Reason.EMPTY -> R.string.error_url_empty
        PlaylistUrl.Reason.TOO_LONG -> R.string.error_url_too_long
        PlaylistUrl.Reason.BAD_FORMAT -> R.string.error_url_format
        PlaylistUrl.Reason.UNSUPPORTED_SCHEME -> R.string.error_url_scheme
    },
)
