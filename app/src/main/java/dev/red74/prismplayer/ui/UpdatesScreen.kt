package dev.red74.prismplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaLibraryInfo
import dev.red74.prismplayer.BuildConfig
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.UpdateCheck
import dev.red74.prismplayer.device.CodecScan

/**
 * Checks the web for a newer release of the app and of its video/audio engine, and lists what this device decodes.
 * It only reports: a new release is built and installed separately.
 */
@Composable
fun UpdatesScreen(onBack: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val report by produceState<UpdateCheck.Report?>(null, attempt) {
        value = null
        value = UpdateCheck.check(BuildConfig.BUILD_LABEL, MediaLibraryInfo.VERSION)
    }
    val codecs = remember { CodecScan.scan() }
    val firstFocus = remember { FocusRequester() }
    RequestFocusOnEnter(firstFocus)

    PrismBackground {
        GlassPanel(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                    PrismText(text = stringResource(R.string.updates_title), size = 30.sp, weight = FontWeight.Bold)
                }
                LazyColumn(
                    Modifier.fillMaxSize().depthFocus().confineFocus(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        GlassButton(
                            text = stringResource(R.string.updates_check),
                            onClick = { attempt++ },
                            focusRequester = firstFocus,
                            emphasized = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val r = report
                    item {
                        InfoRow(
                            title = stringResource(R.string.updates_app),
                            body = when {
                                r == null -> stringResource(R.string.updates_checking)
                                r.offline -> stringResource(R.string.updates_offline)
                                r.appLatest == null -> stringResource(R.string.updates_app_unknown, r.appCurrent)
                                r.appNewer -> stringResource(R.string.updates_app_new, r.appLatest, r.appCurrent) +
                                    "\n" + UpdateCheck.DOWNLOAD_BASE + r.appLatest + "/" + UpdateCheck.APK_NAME
                                else -> stringResource(R.string.updates_app_ok, r.appCurrent)
                            },
                            highlight = r?.appNewer == true,
                        )
                    }
                    item {
                        InfoRow(
                            title = stringResource(R.string.updates_engine),
                            body = when {
                                r == null -> stringResource(R.string.updates_checking)
                                r.offline -> stringResource(R.string.updates_offline)
                                r.engineLatest == null -> stringResource(R.string.updates_engine_unknown, r.engineCurrent)
                                r.engineNewer -> stringResource(R.string.updates_engine_new, r.engineLatest, r.engineCurrent)
                                else -> stringResource(R.string.updates_engine_ok, r.engineCurrent)
                            },
                            highlight = r?.engineNewer == true,
                        )
                    }
                    item {
                        PrismText(
                            text = stringResource(R.string.updates_device),
                            size = 20.sp,
                            weight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(codecs.size) { index ->
                        val row = codecs[index]
                        InfoRow(
                            title = row.label,
                            body = stringResource(
                                when {
                                    !row.supported -> R.string.codec_no
                                    row.hardware -> R.string.codec_hw
                                    else -> R.string.codec_sw
                                },
                            ),
                            highlight = false,
                        )
                    }
                    item {
                        PrismText(
                            text = stringResource(R.string.updates_note),
                            size = 15.sp,
                            color = PrismColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, body: String, highlight: Boolean) {
    GlassSurface(
        onClick = {},
        tint = if (highlight) PrismColors.Accent else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrismText(text = title, size = 18.sp, weight = FontWeight.SemiBold)
            PrismText(text = body, size = 16.sp, color = PrismColors.TextSecondary)
        }
    }
}
