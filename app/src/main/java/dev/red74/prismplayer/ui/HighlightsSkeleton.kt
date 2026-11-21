package dev.red74.prismplayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R

/** True while the guide is being downloaded and no featured event is known yet (nothing saved from before either). */
val LocalHighlightsLoading = compositionLocalOf { false }

/**
 * What the featured page shows while its events are being found: a large card and a row of cards that breathe softly,
 * in the shape of the real ones, and a line saying what is going on. One animated value drives every placeholder, so
 * it costs almost nothing on a small TV box (no recomposition: the value is only read when drawing).
 */
@Composable
fun HighlightsSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonPulse",
    )
    Column(modifier.fillMaxWidth().testTag("skeleton"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Placeholder(Modifier.fillMaxWidth(), 230.dp, 28.dp) { pulse }
        PrismText(text = stringResource(R.string.highlights_loading), size = 20.sp, color = PrismColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { Placeholder(Modifier.width(340.dp), 156.dp, 18.dp) { pulse } }
        }
    }
}

@Composable
private fun Placeholder(modifier: Modifier, height: Dp, radius: Dp, pulse: () -> Float) {
    Box(
        modifier
            .height(height)
            .graphicsLayer { alpha = pulse() }
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(radius)),
    )
}
