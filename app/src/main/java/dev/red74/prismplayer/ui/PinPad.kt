package dev.red74.prismplayer.ui

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import dev.red74.prismplayer.data.ParentalPin

/** The digit a remote or keyboard key stands for, or null. */
internal fun pinDigitOf(nativeKeyCode: Int): Char? = when (nativeKeyCode) {
    in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> '0' + (nativeKeyCode - AndroidKey.KEYCODE_0)
    in AndroidKey.KEYCODE_NUMPAD_0..AndroidKey.KEYCODE_NUMPAD_9 -> '0' + (nativeKeyCode - AndroidKey.KEYCODE_NUMPAD_0)
    else -> null
}

/**
 * Four dots and a keypad (also the digit keys of the remote). When the fourth digit is in, [onComplete] gets the
 * PIN and the dots clear. [wrong] (set by the caller after a wrong PIN) turns the empty dots red.
 */
@Composable
internal fun PinPad(
    title: String,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    wrong: Boolean = false,
    firstFocus: FocusRequester? = null,
) {
    var digits by remember { mutableStateOf("") }
    fun push(d: Char) {
        UiSounds.play(UiSounds.Kind.SELECT)
        val next = digits + d
        if (next.length >= ParentalPin.LENGTH) {
            digits = ""
            onComplete(next)
        } else digits = next
    }
    Column(
        modifier.onPreviewKeyEvent { event ->
            val d = pinDigitOf(event.key.nativeKeyCode)
            if (event.type == KeyEventType.KeyDown && d != null) { push(d); true } else false
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PrismText(text = title, size = 26.sp, weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("pinDots")) {
            repeat(ParentalPin.LENGTH) { i ->
                Box(
                    Modifier.size(18.dp).background(
                        when {
                            wrong && digits.isEmpty() -> PrismColors.Danger
                            i < digits.length -> PrismColors.Accent
                            else -> PrismColors.TextSecondary.copy(alpha = 0.35f)
                        },
                        CircleShape,
                    ),
                )
            }
        }
        val rows = listOf("123", "456", "789")
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { c, ch ->
                    GlassButton(
                        text = ch.toString(),
                        onClick = { push(ch) },
                        modifier = Modifier.width(96.dp).testTag("pin$ch"),
                        focusRequester = if (r == 0 && c == 1) firstFocus else null,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(
                text = "⌫",
                onClick = { UiSounds.play(UiSounds.Kind.BACK); digits = digits.dropLast(1) },
                modifier = Modifier.width(96.dp).testTag("pinDelete"),
            )
            GlassButton(text = "0", onClick = { push('0') }, modifier = Modifier.width(96.dp).testTag("pin0"))
            Spacer(Modifier.width(96.dp))
        }
    }
}
