package dev.red74.prismplayer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.composed
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
    val Ok = Color(0xFF6FE3B0)
    val Warn = Color(0xFFFFC857)
    /** Warm terracotta orange used for favourites. */
    val Favorite = Color(0xFFD97757)
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
        // The installed build, always readable at the bottom of the page.
        PrismText(
            text = androidx.compose.ui.res.stringResource(
                dev.red74.prismplayer.R.string.version_label,
                dev.red74.prismplayer.BuildConfig.BUILD_LABEL,
            ),
            size = 13.sp,
            color = PrismColors.TextSecondary,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
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
    tint: Color? = null,
    subtitleColor: Color = PrismColors.TextSecondary,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // iOS-like feel: focus lifts the button with a slight overshoot, a press squeezes it back.
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.96f
            focused -> 1.06f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "glassButtonScale",
    )
    val shape = RoundedCornerShape(20.dp)
    val fill = when {
        tint != null && focused -> Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.30f)))
        tint != null -> Brush.linearGradient(listOf(tint.copy(alpha = 0.26f), tint.copy(alpha = 0.10f)))
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

    Column(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    tint != null -> tint.copy(alpha = 0.85f)
                    else -> Color.White.copy(alpha = 0.22f)
                },
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        PrismText(text = text, size = 22.sp, weight = FontWeight.SemiBold, maxLines = 1)
        if (subtitle != null) {
            PrismText(text = subtitle, size = 16.sp, color = subtitleColor, maxLines = 1)
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

/**
 * A focusable glass tile that can hold anything (a channel row, a poster card). It behaves like [GlassButton]:
 * spring lift when focused, a squeeze when pressed, a white border for the focus, a tint for warnings.
 */
@Composable
fun GlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    selected: Boolean = false,
    tint: Color? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 18.dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    focusScale: Float = 1.03f,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.97f
            focused -> focusScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "glassSurfaceScale",
    )
    val shape = RoundedCornerShape(cornerRadius)
    val base = tint ?: PrismColors.Accent
    val fill = when {
        focused -> Brush.linearGradient(listOf(base.copy(alpha = 0.50f), PrismColors.AccentAlt.copy(alpha = 0.34f)))
        tint != null -> Brush.linearGradient(listOf(tint.copy(alpha = 0.26f), tint.copy(alpha = 0.10f)))
        selected -> Brush.linearGradient(listOf(base.copy(alpha = 0.28f), PrismColors.AccentAlt.copy(alpha = 0.18f)))
        else -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f)))
    }
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    tint != null -> tint.copy(alpha = 0.85f)
                    else -> Color.White.copy(alpha = 0.20f)
                },
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(contentPadding),
        content = content,
    )
}

/** Poster or channel logo from the playlist; a soft gradient with the initial shows while it loads or if it is missing. */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier, fit: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF26356B), Color(0xFF3A1F6B)))),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        PrismText(
            text = title.trim().take(1).uppercase(),
            size = 26.sp,
            weight = FontWeight.Bold,
            color = PrismColors.TextSecondary,
        )
        if (!url.isNullOrBlank()) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (fit) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Keeps the D-pad inside this section: the arrows stop at its edge in every direction (Left and Right too unless
 * [horizontal] is false) instead of jumping to a neighbouring section. Moving between sections is done only with
 * Back (up/out) and OK (down/in). This is the rule for
 * every list, grid and panel in the app.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.confineFocus(horizontal: Boolean = true): Modifier = this
    .focusProperties {
        exit = { direction ->
            val vertical = direction == androidx.compose.ui.focus.FocusDirection.Up ||
                direction == androidx.compose.ui.focus.FocusDirection.Down
            val sideways = direction == androidx.compose.ui.focus.FocusDirection.Left ||
                direction == androidx.compose.ui.focus.FocusDirection.Right
            if (vertical || (horizontal && sideways)) FocusRequester.Cancel else FocusRequester.Default
        }
    }
    .focusGroup()

/**
 * Depth between sections: the section that holds the focus comes forward (full size, full brightness) and the
 * others sit slightly behind (smaller, dimmer). Used on every group of focusable sections, side by side or stacked.
 */
fun Modifier.depthFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val depth by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(320),
        label = "depth",
    )
    Modifier
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            val s = 0.94f + 0.06f * depth
            scaleX = s
            scaleY = s
            alpha = 0.55f + 0.45f * depth
        }
}
