package dev.red74.prismplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sin
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R

/** "Close the app?" shown when back is pressed on the home screen. Focus starts on "No". */
@Composable
fun ExitScreen(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val noFocus = remember { FocusRequester() }
    RequestFocusOnEnter(noFocus)
    var closing by remember { mutableStateOf(false) }
    // Once "Yes" is pressed there is no way back: the screen dissolves to pure black while the closing sting plays.
    BackHandler(enabled = true) { if (!closing) onDismiss() }

    val fade = remember { Animatable(0f) }
    val context = LocalContext.current
    DisposableEffect(closing) {
        if (closing) UiSounds.enabled = false
        val player = if (!closing) null else runCatching {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            MediaPlayer.create(context, dev.red74.prismplayer.R.raw.prism_outro, attributes, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)?.apply {
                val v = if (UiSounds.muted) 0f else UiSounds.MASTER
                setVolume(v, v)
                start()
            }
        }.getOrNull()
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }
    LaunchedEffect(closing) {
        if (closing) {
            fade.animateTo(1f, tween(durationMillis = CLOSING_MS, easing = LinearEasing))
            onConfirm()
        }
    }
    val dust = remember {
        val random = java.util.Random(11)
        FloatArray(260 * 4) { random.nextFloat() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
    PrismBackground(
        Modifier.graphicsLayer {
            val t = (fade.value / 0.85f).coerceIn(0f, 1f)
            alpha = 1f - t * t
            val s = 1f - 0.08f * t
            scaleX = s
            scaleY = s
        },
    ) {
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
    // The pieces of the screen drift upwards and vanish as it dissolves.
    if (closing) {
        Canvas(Modifier.fillMaxSize()) {
            val t = fade.value
            val strength = sin(Math.PI.toFloat() * t.coerceIn(0f, 1f)).coerceAtLeast(0f)
            var i = 0
            while (i < dust.size) {
                val x = dust[i] * size.width
                val y = (dust[i + 1] - t * (0.15f + dust[i + 2] * 0.35f)) * size.height
                val r = (1.5f + dust[i + 3] * 3.5f) * density
                drawCircle(Color(0xFFBFD0FF).copy(alpha = 0.55f * strength), r, Offset(x, y))
                i += 4
            }
        }
    }
    }
}

private const val CLOSING_MS = 7_400
