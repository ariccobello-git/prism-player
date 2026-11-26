package dev.red74.prismplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.red74.prismplayer.R
import dev.red74.prismplayer.data.ParentalPin

/** What the PIN settings screen is asking at the moment. */
private enum class PinStage { MENU, CURRENT_TO_CHANGE, CURRENT_TO_REMOVE, NEW, CONFIRM }

/** Set, change or remove the PIN that keeps the adult groups closed. */
@Composable
fun PinSettingsScreen(onBack: () -> Unit) {
    var stage by remember { mutableStateOf(PinStage.MENU) }
    var first by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }
    var isSet by remember { mutableStateOf(ParentalPin.isSet) }
    val padFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    BackHandler(enabled = true) {
        UiSounds.play(UiSounds.Kind.BACK)
        if (stage == PinStage.MENU) onBack() else { stage = PinStage.MENU; wrong = false; mismatch = false }
    }
    RequestFocusOnEnter(if (stage == PinStage.MENU) menuFocus else padFocus, stage)

    PrismBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.widthIn(max = 640.dp)) {
                Column(
                    Modifier.padding(40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (stage) {
                        PinStage.MENU -> {
                            PrismText(text = stringResource(R.string.pin_title), size = 34.sp, weight = FontWeight.Bold)
                            PrismText(text = stringResource(R.string.pin_hint), size = 18.sp, color = PrismColors.TextSecondary)
                            if (!isSet) {
                                GlassButton(text = stringResource(R.string.pin_set), onClick = { first = ""; stage = PinStage.NEW }, focusRequester = menuFocus, emphasized = true)
                            } else {
                                GlassButton(text = stringResource(R.string.pin_change), onClick = { wrong = false; stage = PinStage.CURRENT_TO_CHANGE }, focusRequester = menuFocus, emphasized = true)
                                GlassButton(text = stringResource(R.string.pin_remove), onClick = { wrong = false; stage = PinStage.CURRENT_TO_REMOVE }, danger = true)
                            }
                            GlassButton(text = stringResource(R.string.action_back), onClick = onBack)
                        }
                        PinStage.CURRENT_TO_CHANGE, PinStage.CURRENT_TO_REMOVE -> {
                            PinPad(
                                title = stringResource(R.string.pin_enter_current),
                                wrong = wrong,
                                firstFocus = padFocus,
                                onComplete = { pin ->
                                    if (ParentalPin.check(pin)) {
                                        wrong = false
                                        if (stage == PinStage.CURRENT_TO_REMOVE) {
                                            ParentalPin.clear(); isSet = false; stage = PinStage.MENU
                                        } else { first = ""; stage = PinStage.NEW }
                                    } else { UiSounds.play(UiSounds.Kind.ERROR); wrong = true }
                                },
                            )
                            if (wrong) PrismText(text = stringResource(R.string.pin_wrong), size = 18.sp, color = PrismColors.Danger)
                        }
                        PinStage.NEW -> {
                            PinPad(
                                title = stringResource(R.string.pin_enter_new),
                                firstFocus = padFocus,
                                onComplete = { pin -> first = pin; mismatch = false; stage = PinStage.CONFIRM },
                            )
                        }
                        PinStage.CONFIRM -> {
                            PinPad(
                                title = stringResource(R.string.pin_confirm_new),
                                wrong = mismatch,
                                firstFocus = padFocus,
                                onComplete = { pin ->
                                    if (pin == first && ParentalPin.set(pin)) { isSet = true; stage = PinStage.MENU }
                                    else { UiSounds.play(UiSounds.Kind.ERROR); mismatch = true; first = ""; stage = PinStage.NEW }
                                },
                            )
                        }
                    }
                    if (stage == PinStage.NEW && mismatch) PrismText(text = stringResource(R.string.pin_mismatch), size = 18.sp, color = PrismColors.Danger)
                }
            }
        }
    }
}

/**
 * The PIN asked before an adult group opens. A right PIN keeps the groups open for a while ([ParentalPin.OPEN_MS])
 * and calls [onOpen]; Back closes the window.
 */
@Composable
fun PinUnlockDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    var wrong by remember { mutableStateOf(false) }
    val padFocus = remember { FocusRequester() }
    RequestFocusOnEnter(padFocus)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassPanel(Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinPad(
                    title = stringResource(R.string.pin_enter),
                    wrong = wrong,
                    firstFocus = padFocus,
                    onComplete = { pin ->
                        if (ParentalPin.check(pin)) { ParentalPin.openFor(); onOpen() }
                        else { UiSounds.play(UiSounds.Kind.ERROR); wrong = true }
                    },
                )
                if (wrong) PrismText(text = stringResource(R.string.pin_wrong), size = 18.sp, color = PrismColors.Danger)
            }
        }
    }
}
