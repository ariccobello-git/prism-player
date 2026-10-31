package dev.red74.prismplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.red74.prismplayer.PairUiState
import dev.red74.prismplayer.R
import dev.red74.prismplayer.pairing.QrCode
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun PairScreen(
    state: PairUiState,
    onNewCode: () -> Unit,
    onClose: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    RequestFocusOnEnter(primaryFocus, key = state::class)

    LaunchedEffect(state) {
        if (state is PairUiState.Done || state is PairUiState.Linked) {
            delay(2_500)
            onClose()
        }
    }

    PrismBackground {
        GlassPanel(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state is PairUiState.Showing) {
                    QrCodeView(
                        content = state.qrContent,
                        modifier = Modifier.size(340.dp),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PrismText(
                        text = stringResource(R.string.pair_title),
                        size = 38.sp,
                        weight = FontWeight.Bold,
                    )
                    when (state) {
                        is PairUiState.Showing -> ShowingDetails(state)
                        PairUiState.Starting -> StatusLine(stringResource(R.string.pair_starting))
                        PairUiState.Expired -> StatusLine(stringResource(R.string.pair_expired))
                        PairUiState.Done -> StatusLine(stringResource(R.string.pair_done))
                        PairUiState.Linked -> StatusLine(stringResource(R.string.pair_linked))
                        PairUiState.NoNetwork -> StatusLine(stringResource(R.string.pair_no_network))
                        PairUiState.Failed -> StatusLine(stringResource(R.string.pair_failed))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassButton(
                            text = stringResource(R.string.pair_new_code),
                            onClick = onNewCode,
                            focusRequester = primaryFocus,
                            emphasized = true,
                        )
                        GlassButton(
                            text = stringResource(R.string.action_back),
                            onClick = onClose,
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    PrismText(text = text, size = 22.sp, color = PrismColors.TextSecondary)
}

@Composable
private fun ShowingDetails(state: PairUiState.Showing) {
    PrismText(stringResource(R.string.pair_step_1), size = 20.sp)
    PrismText(stringResource(R.string.pair_step_2), size = 20.sp)
    PrismText(stringResource(R.string.pair_step_3), size = 20.sp)
    Spacer(Modifier.height(6.dp))
    PrismText(
        text = stringResource(R.string.pair_code_label),
        size = 16.sp,
        color = PrismColors.TextSecondary,
    )
    Text(
        text = state.codeDisplay,
        color = PrismColors.TextPrimary,
        fontSize = 44.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    PrismText(
        text = stringResource(R.string.pair_expires_in, formatCountdown(state.remainingSeconds)),
        size = 20.sp,
        color = PrismColors.Accent,
    )
    PrismText(
        text = stringResource(R.string.pair_manual, state.address),
        size = 16.sp,
        color = PrismColors.TextSecondary,
    )
    PrismText(
        text = stringResource(R.string.pair_security_note),
        size = 16.sp,
        color = PrismColors.TextSecondary,
    )
}

private fun formatCountdown(totalSeconds: Int): String =
    String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)

/** Draws the QR modules directly on a Canvas: black on white with a quiet zone, scannable at TV distance. */
@Composable
private fun QrCodeView(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { QrCode.encode(content) }
    Canvas(
        modifier
            .background(Color.White, RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        val modules = matrix.size
        val cell = (size.minDimension / modules).toInt().coerceAtLeast(1).toFloat()
        val origin = (size.minDimension - cell * modules) / 2f
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(origin + x * cell, origin + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
