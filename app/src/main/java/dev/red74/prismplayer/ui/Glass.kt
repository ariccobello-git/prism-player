package dev.red74.prismplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/**
 * Prism Player's glass look, tuned for a 10-foot UI:
 * translucent gradient panels with a light edge over a soft gradient backdrop, large type,
 * and an unmistakable focus state (scale + bright border) for D-pad navigation.
 *
 * Real backdrop blur is deliberately not used: it is unavailable below Android 12 and too heavy for
 * entry-level sticks, so the depth comes from layered translucency instead.
 */
object PrismColors {
    val Bg0 = Color(0xFF070B14)
    val Bg1 = Color(0xFF111A33)
    val Bg2 = Color(0xFF1C1140)
    val Accent = Color(0xFF7C9CFF)
    val AccentAlt = Color(0xFFB57CFF)
    val TextPrimary = Color(0xFFF4F6FF)
    val TextSecondary = Color(0xFFB4BBD6)
    val Danger = Color(0xFFFF7C8A)
}

@Composable
fun PrismTheme(content: @Composable () -> Unit) {
    // The layouts are drawn for a 1280x720 dp canvas. TV sticks often report a smaller one
    // (a 1080p stick at 320 dpi is 960x540 dp), so scale the whole UI down to fit instead of clipping text.
    // The system font scale is ignored on purpose: on a TV it only pushes text off the screen.
    val configuration = LocalConfiguration.current
    val base = LocalDensity.current
    val fit = minOf(
        configuration.screenWidthDp / DESIGN_WIDTH_DP,
        configuration.screenHeightDp / DESIGN_HEIGHT_DP,
        1f,
    ).coerceAtLeast(0.5f)
    CompositionLocalProvider(LocalDensity provides Density(base.density * fit, fontScale = 1f)) {
        ThemedContent(content)
    }
}

private const val DESIGN_WIDTH_DP = 1280f
private const val DESIGN_HEIGHT_DP = 720f

@Composable
private fun ThemedContent(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrismColors.Accent,
            background = PrismColors.Bg0,
            surface = PrismColors.Bg1,
            onSurface = PrismColors.TextPrimary,
            onBackground = PrismColors.TextPrimary,
        ),
        content = content,
    )
}

@Composable
fun PrismText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = PrismColors.TextPrimary,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PrismBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(PrismColors.Bg0, PrismColors.Bg1, PrismColors.Bg2))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.6f
            val first = Offset(size.width * 0.15f, size.height * 0.2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(PrismColors.Accent.copy(alpha = 0.32f), Color.Transparent),
                    center = first,
                    radius = radius,
                ),
                radius = radius,
                center = first,
            )
            val second = Offset(size.width * 0.9f, size.height * 0.85f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(PrismColors.AccentAlt.copy(alpha = 0.28f), Color.Transparent),
                    center = second,
                    radius = radius,
                ),
                radius = radius,
                center = second,
            )
        }
        content()
    }
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.05f)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f)),
                ),
                shape,
            ),
        content = content,
    )
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
    emphasized: Boolean = false,
    danger: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "glassButtonScale")
    val shape = RoundedCornerShape(20.dp)
    val fill = when {
        focused && danger -> Brush.linearGradient(
            listOf(PrismColors.Danger.copy(alpha = 0.6f), PrismColors.Danger.copy(alpha = 0.35f)),
        )
        focused -> Brush.linearGradient(
            listOf(PrismColors.Accent.copy(alpha = 0.55f), PrismColors.AccentAlt.copy(alpha = 0.45f)),
        )
        emphasized -> Brush.linearGradient(
            listOf(PrismColors.Accent.copy(alpha = 0.28f), PrismColors.AccentAlt.copy(alpha = 0.18f)),
        )
        else -> Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f)),
        )
    }
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.22f),
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        PrismText(text = text, size = 22.sp, weight = FontWeight.SemiBold, maxLines = 1)
        if (subtitle != null) {
            PrismText(text = subtitle, size = 16.sp, color = PrismColors.TextSecondary, maxLines = 1)
        }
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Column(modifier) {
        PrismText(text = label, size = 16.sp, color = PrismColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = PrismColors.TextPrimary, fontSize = 22.sp),
            cursorBrush = SolidColor(PrismColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.22f),
                    shape = shape,
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/** Moves D-pad focus to [requester] once its target is on screen (one frame after composition). */
@Composable
fun RequestFocusOnEnter(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}
