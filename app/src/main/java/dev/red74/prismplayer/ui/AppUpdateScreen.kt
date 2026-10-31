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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.EngineInfo
import dev.red74.prismplayer.R
import dev.red74.prismplayer.UpdateState
import dev.red74.prismplayer.data.AppUpdater

/**
 * The app update, as a small centred card like the "Close the app?" one: the check, the question "Update?", the
 * download with its percentage and anything that went wrong. Back always means "Later".
 */
@Composable
fun AppUpdateScreen(
    state: UpdateState,
    onInstall: () -> Unit,
    onRetryCheck: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val mainFocus = remember { FocusRequester() }
    // Focus goes to the main button again whenever the card changes (checking → result, download → installer).
    RequestFocusOnEnter(mainFocus, state::class)

    val title: String
    val message: String
    val main: Pair<String, () -> Unit>
    var second: Pair<String, () -> Unit>? = null
    val close = stringResource(R.string.action_close)
    val later = stringResource(R.string.update_later)
    val update = stringResource(R.string.update_install)
    val retry = stringResource(R.string.action_retry)
    when (state) {
        UpdateState.Idle, UpdateState.Checking -> {
            title = stringResource(R.string.update_title)
            message = stringResource(R.string.update_checking)
            main = stringResource(R.string.action_cancel) to onClose
        }
        UpdateState.Offline -> {
            title = stringResource(R.string.update_title)
            message = stringResource(R.string.update_offline)
            main = retry to onRetryCheck
            second = close to onClose
        }
        is UpdateState.UpToDate -> {
            title = stringResource(R.string.update_ok_title)
            message = stringResource(R.string.update_ok_message, state.installed) + engineLine(state.engine)
            main = stringResource(R.string.action_ok) to onClose
        }
        is UpdateState.Available -> {
            title = stringResource(R.string.update_new_title)
            message = stringResource(R.string.update_new_message, state.release.tag, state.installed) +
                engineLine(state.engine)
            main = update to onInstall
            second = later to onClose
        }
        is UpdateState.Downloading -> {
            title = stringResource(R.string.update_new_title)
            message = if (state.percent < 0) stringResource(R.string.update_downloading, state.release.tag)
            else stringResource(R.string.update_downloading_percent, state.release.tag, state.percent)
            main = stringResource(R.string.action_cancel) to onClose
        }
        is UpdateState.InstallerOpened -> {
            title = stringResource(R.string.update_new_title)
            message = stringResource(R.string.update_installer_opened)
            main = update to onInstall
            second = later to onClose
        }
        is UpdateState.Failed -> {
            title = stringResource(R.string.update_failed_title)
            message = stringResource(
                when (state.problem) {
                    AppUpdater.Problem.DOWNLOAD -> R.string.update_failed_download
                    AppUpdater.Problem.CORRUPT -> R.string.update_failed_corrupt
                    AppUpdater.Problem.SIGNATURE -> R.string.update_failed_signature
                    AppUpdater.Problem.INSTALLER -> R.string.update_failed_installer
                    AppUpdater.Problem.OLD_ANDROID -> R.string.update_failed_old_android
                },
            )
            val canRetry = state.release != null &&
                state.problem != AppUpdater.Problem.SIGNATURE && state.problem != AppUpdater.Problem.OLD_ANDROID
            main = if (canRetry) retry to onInstall else close to onClose
            if (canRetry) second = close to onClose
        }
    }

    PrismBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.widthIn(max = 760.dp)) {
                Column(
                    Modifier.padding(48.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrismText(text = title, size = 34.sp, weight = FontWeight.Bold)
                    PrismText(text = message, size = 20.sp, color = PrismColors.TextSecondary)
                    Row(Modifier.confineFocus(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        GlassButton(
                            text = main.first,
                            onClick = main.second,
                            focusRequester = mainFocus,
                            emphasized = true,
                        )
                        second?.let { (label, action) ->
                            GlassButton(text = label, onClick = action)
                        }
                    }
                }
            }
        }
    }
}

/** One more paragraph about the video and audio engine, when the button's check read its latest version. */
@Composable
private fun engineLine(engine: EngineInfo?): String = when {
    engine == null -> ""
    engine.latest == null -> "\n\n" + stringResource(R.string.update_engine_unknown, engine.installed)
    engine.newer -> "\n\n" + stringResource(R.string.update_engine_new, engine.latest, engine.installed)
    else -> "\n\n" + stringResource(R.string.update_engine_ok, engine.installed)
}
