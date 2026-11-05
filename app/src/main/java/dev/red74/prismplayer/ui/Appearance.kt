package dev.red74.prismplayer.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.red74.prismplayer.R
import java.util.Locale

/** One base colour set for the whole app: the backdrop gradient and the two accent colours. */
class Palette(
    @StringRes val nameRes: Int,
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val accent: Color,
    val accentAlt: Color,
    /** Adds a faint drawn scene (Roman arches) to the backdrop. */
    val arches: Boolean = false,
)

/** The user's choices of language and colours, kept between launches. Reading them is free (plain memory). */
object Appearance {
    val palettes: List<Palette> = listOf(
        Palette(R.string.palette_blue, Color(0xFF070B14), Color(0xFF111A33), Color(0xFF1C1140), Color(0xFF7C9CFF), Color(0xFFB57CFF)),
        Palette(R.string.palette_violet, Color(0xFF0D0716), Color(0xFF22103A), Color(0xFF3A1250), Color(0xFFB57CFF), Color(0xFFFF7CD8)),
        Palette(R.string.palette_red, Color(0xFF140708), Color(0xFF33121A), Color(0xFF45101C), Color(0xFFFF7C8A), Color(0xFFFFA06B)),
        Palette(R.string.palette_orange, Color(0xFF140C06), Color(0xFF30190A), Color(0xFF3F1F0A), Color(0xFFFFA24D), Color(0xFFFFD37C)),
        Palette(R.string.palette_green, Color(0xFF06130C), Color(0xFF0F2C1D), Color(0xFF0B3A2A), Color(0xFF5FE3A0), Color(0xFF9BE35F)),
        Palette(R.string.palette_teal, Color(0xFF061214), Color(0xFF0E2A33), Color(0xFF0A3A44), Color(0xFF4FD8E8), Color(0xFF5FA8FF)),
        Palette(R.string.palette_pink, Color(0xFF140812), Color(0xFF331430), Color(0xFF44123A), Color(0xFFFF7CC0), Color(0xFFC77CFF)),
        Palette(R.string.palette_gold, Color(0xFF0C0C0E), Color(0xFF1C1C22), Color(0xFF26242A), Color(0xFFE6C36A), Color(0xFFBFC6D6)),
        Palette(R.string.palette_burgundy, Color(0xFF10060A), Color(0xFF2A0F1C), Color(0xFF3A0E26), Color(0xFFE0577F), Color(0xFFFF8FA8)),
        Palette(R.string.palette_lime, Color(0xFF0B1205), Color(0xFF1D2B0B), Color(0xFF2A3A0A), Color(0xFFC4F04D), Color(0xFF7BE35F)),
        Palette(R.string.palette_ice, Color(0xFF070F16), Color(0xFF12263A), Color(0xFF16344A), Color(0xFF9BD8FF), Color(0xFFD0F0FF)),
        Palette(R.string.palette_indigo, Color(0xFF080815), Color(0xFF16164A), Color(0xFF1E1A66), Color(0xFF8A8CFF), Color(0xFF5F7BFF)),
        Palette(R.string.palette_coral, Color(0xFF150A09), Color(0xFF361A16), Color(0xFF47201A), Color(0xFFFF8F6B), Color(0xFFFFB59B)),
        Palette(R.string.palette_mint, Color(0xFF06130F), Color(0xFF0F2E26), Color(0xFF123D33), Color(0xFF8FF0D0), Color(0xFF5FE3C0)),
        Palette(R.string.palette_coffee, Color(0xFF120C08), Color(0xFF2A1D14), Color(0xFF3A271A), Color(0xFFD9A066), Color(0xFFB8845A)),
        Palette(R.string.palette_silver, Color(0xFF0B0D12), Color(0xFF1B202B), Color(0xFF262C3A), Color(0xFFD5DBEA), Color(0xFF9BA8C4)),
        // AS Roma: the club's yellow #FBBA00 and red #970A2C (brandcolorcode.com), over a dark red backdrop.
        Palette(R.string.palette_roma, Color(0xFF0D0507), Color(0xFF2A0A14), Color(0xFF4A0A1E), Color(0xFFFBBA00), Color(0xFFB5123A), arches = true),
    )

    private var prefs: android.content.SharedPreferences? = null
    private val index = mutableIntStateOf(0)

    val paletteIndex: Int get() = index.intValue
    val palette: Palette get() = palettes[index.intValue.coerceIn(palettes.indices)]

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("prism_ui", Context.MODE_PRIVATE)
        index.intValue = runCatching { prefs?.getInt("palette", 0) }.getOrNull()?.coerceIn(palettes.indices) ?: 0
    }

    fun setPalette(value: Int) {
        index.intValue = value.coerceIn(palettes.indices)
        runCatching { prefs?.edit()?.putInt("palette", index.intValue)?.apply() }
    }

    /** "it" or "en", or null while the TV's own language is followed. */
    fun savedLanguage(context: Context): String? =
        runCatching { context.applicationContext.getSharedPreferences("prism_ui", Context.MODE_PRIVATE).getString("lang", null) }.getOrNull()

    /** Set when the language was just changed: the restarted Home page puts the cursor back on that flag. */
    @Volatile
    var flagToFocus: String? = null

    fun saveLanguage(context: Context, lang: String) {
        runCatching { context.applicationContext.getSharedPreferences("prism_ui", Context.MODE_PRIVATE).edit().putString("lang", lang).apply() }
    }

    /** [base] with the chosen language applied (the TV's own language when none was chosen). */
    fun localized(base: Context): Context {
        val lang = savedLanguage(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale(lang))
        return base.createConfigurationContext(config)
    }

    /** The language shown right now: the chosen one, else the TV's, with Italian for Italian TVs and English for the rest. */
    fun currentLanguage(context: Context): String =
        savedLanguage(context) ?: if (Locale.getDefault().language == "it") "it" else "en"
}

/** A round, focusable button: lifts and gets a white ring on focus, like the glass buttons. */
@Composable
fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "roundButtonScale",
    )
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
            .size(size)
            .clip(CircleShape)
            .border(
                width = if (focused) 4.dp else if (selected) 3.dp else 1.dp,
                color = when {
                    focused -> Color.White
                    selected -> PrismColors.Accent
                    else -> Color.White.copy(alpha = 0.25f)
                },
                shape = CircleShape,
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Italian or British flag filling the whole circle. */
@Composable
fun FlagButton(italian: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    RoundButton(onClick = onClick, modifier = modifier, selected = selected, focusRequester = focusRequester) {
        Canvas(Modifier.fillMaxSize()) {
            if (italian) drawItalianFlag() else drawBritishFlag()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawItalianFlag() {
    val band = size.width / 3f
    drawRect(Color(0xFF009246), Offset.Zero, Size(band + 1f, size.height))
    drawRect(Color(0xFFFFFFFF), Offset(band, 0f), Size(band + 1f, size.height))
    drawRect(Color(0xFFCE2B37), Offset(band * 2f, 0f), Size(band + 1f, size.height))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBritishFlag() {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFF012169))
    val white = w * 0.2f
    val red = w * 0.11f
    drawLine(Color.White, Offset(0f, 0f), Offset(w, h), white)
    drawLine(Color.White, Offset(w, 0f), Offset(0f, h), white)
    drawLine(Color(0xFFC8102E), Offset(0f, 0f), Offset(w, h), red * 0.5f)
    drawLine(Color(0xFFC8102E), Offset(w, 0f), Offset(0f, h), red * 0.5f)
    drawRect(Color.White, Offset(w * 0.4f, 0f), Size(w * 0.2f, h))
    drawRect(Color.White, Offset(0f, h * 0.4f), Size(w, h * 0.2f))
    drawRect(Color(0xFFC8102E), Offset(w * 0.45f, 0f), Size(w * 0.1f, h))
    drawRect(Color(0xFFC8102E), Offset(0f, h * 0.45f), Size(w, h * 0.1f))
}

/** Pick one of the eight base colour sets, see it on a small app page, then confirm or cancel. */
@Composable
fun AppearanceScreen(onDone: () -> Unit) {
    var chosen by remember { mutableIntStateOf(Appearance.paletteIndex) }
    val first = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    RequestFocusOnEnter(first)
    val preview = Appearance.palettes[chosen]

    PrismBackground {
        GlassPanel(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PrismText(text = stringResource(R.string.appearance_title), size = 30.sp, weight = FontWeight.Bold)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxHeight().confineFocus(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrismText(text = stringResource(R.string.appearance_pick), size = 18.sp, color = PrismColors.TextSecondary)
                        for (rowStart in 0 until Appearance.palettes.size step 6) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                                for (i in rowStart until minOf(rowStart + 6, Appearance.palettes.size)) {
                                    val p = Appearance.palettes[i]
                                    RoundButton(
                                        size = 56.dp,
                                        onClick = {
                                            chosen = i
                                            // OK on a colour moves the cursor to Confirm, keeping that colour.
                                            runCatching { confirm.requestFocus() }
                                        },
                                        selected = chosen == i,
                                        focusRequester = if (i == Appearance.paletteIndex) first else null,
                                        onFocused = { chosen = i },
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().background(
                                                Brush.linearGradient(listOf(p.accent, p.accentAlt)),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        PrismText(text = stringResource(preview.nameRes), size = 22.sp, weight = FontWeight.SemiBold, color = preview.accent)
                    }
                    PalettePreview(preview, Modifier.weight(0.58f).fillMaxHeight())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    GlassButton(
                        text = stringResource(R.string.appearance_confirm),
                        emphasized = true,
                        focusRequester = confirm,
                        onClick = {
                            Appearance.setPalette(chosen)
                            onDone()
                        },
                    )
                    GlassButton(text = stringResource(R.string.appearance_cancel), onClick = onDone)
                }
            }
        }
    }
}

/**
 * A faint line drawing of Rome in the accent colour: the Colosseum seen from a little above, so its round shape reads
 * (three tiers of arches around the front half, the whole rim as an ellipse), and the Capitoline she-wolf with the twins.
 * Plain strokes, drawn once, never animated.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRome(color: Color) {
    val w = size.width
    val h = size.height
    val line = size.minDimension * 0.003f + 1f
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = line, cap = StrokeCap.Round)
    val soft = color.copy(alpha = 0.12f)
    val fainter = color.copy(alpha = 0.08f)

    // Colosseum: an ellipse seen in perspective, three tiers.
    val cx = w * 0.70f
    val rx = w * 0.28f
    val ry = h * 0.07f
    val tier = h * 0.07f
    val base = h * 0.90f
    val top = base - tier * 3f
    fun oval(y: Float, c: Color) = drawOval(c, Offset(cx - rx, y - ry), Size(rx * 2f, ry * 2f), style = stroke)
    fun front(y: Float, c: Color) = drawArc(c, 0f, 180f, false, Offset(cx - rx, y - ry), Size(rx * 2f, ry * 2f), style = stroke)
    oval(top, soft)
    for (t in 1..2) front(base - tier * t, fainter)
    front(base, soft)
    drawLine(soft, Offset(cx - rx, top), Offset(cx - rx, base), line)
    drawLine(soft, Offset(cx + rx, top), Offset(cx + rx, base), line)
    // The arches of the two lower tiers, closer together towards the sides as the curve turns away.
    val n = 17
    for (t in 0 until 2) {
        val yBase = base - tier * t
        for (i in 1 until n) {
            val a = Math.PI * i / n
            val x = cx + rx * Math.cos(a).toFloat()
            val y = yBase + ry * Math.sin(a).toFloat()
            val half = rx * 0.055f * maxOf(0.3f, Math.sin(a).toFloat())
            val rise = tier * 0.55f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x - half, y)
                lineTo(x - half, y - rise)
                arcTo(androidx.compose.ui.geometry.Rect(x - half, y - rise - half, x + half, y - rise + half), 180f, 180f, false)
                lineTo(x + half, y)
            }
            drawPath(path, soft, style = stroke)
        }
    }
    // The attic: small square windows along the top tier.
    for (i in 1 until n) {
        val a = Math.PI * i / n
        val x = cx + rx * Math.cos(a).toFloat()
        val y = top + tier + ry * Math.sin(a).toFloat()
        val half = rx * 0.02f * maxOf(0.3f, Math.sin(a).toFloat())
        drawRect(fainter, Offset(x - half, y - tier * 0.7f), Size(half * 2f, tier * 0.35f), style = stroke)
    }

    // The she-wolf with Romulus and Remus: simple outlines, units of a 100-wide box.
    val u = w * 0.0023f
    val ox = w * 0.07f
    val oy = h * 0.90f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    val wolf = color.copy(alpha = 0.16f)
    drawOval(wolf, p(20f, -51f), Size(60f * u, 26f * u), style = stroke)                  // body
    drawLine(wolf, p(26f, -44f), p(14f, -60f), line)                                      // neck
    drawOval(wolf, p(2f, -68f), Size(22f * u, 14f * u), style = stroke)                   // head
    drawLine(wolf, p(4f, -62f), p(-6f, -58f), line)                                       // snout
    drawLine(wolf, p(14f, -68f), p(18f, -77f), line)                                      // ear
    drawLine(wolf, p(18f, -77f), p(22f, -67f), line)
    drawLine(wolf, p(30f, -30f), p(28f, 0f), line)                                        // legs
    drawLine(wolf, p(38f, -28f), p(38f, 0f), line)
    drawLine(wolf, p(66f, -30f), p(66f, 0f), line)
    drawLine(wolf, p(74f, -32f), p(78f, 0f), line)
    val tail = androidx.compose.ui.graphics.Path().apply {
        moveTo(p(79f, -44f).x, p(79f, -44f).y)
        quadraticTo(p(94f, -46f).x, p(94f, -46f).y, p(92f, -28f).x, p(92f, -28f).y)
    }
    drawPath(tail, wolf, style = stroke)                                                  // tail
    drawOval(wolf, p(36f, -17f), Size(14f * u, 8f * u), style = stroke)                   // the twins
    drawCircle(wolf, 3.2f * u, p(33f, -14f), style = stroke)
    drawOval(wolf, p(52f, -17f), Size(14f * u, 8f * u), style = stroke)
    drawCircle(wolf, 3.2f * u, p(69f, -14f), style = stroke)
    drawLine(wolf, p(-10f, 0f), p(100f, 0f), line)                                        // the ground
}

/** A small, static picture of the Home page in the colours of [p]. Nothing animates, so it costs nothing. */
@Composable
private fun PalettePreview(p: Palette, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(p.bg0, p.bg1, p.bg2)))
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.7f
            val a = Offset(size.width * 0.1f, size.height * 0.15f)
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.32f), Color.Transparent), a, r), r, a)
            val b = Offset(size.width * 0.95f, size.height * 0.9f)
            drawCircle(Brush.radialGradient(listOf(p.accentAlt.copy(alpha = 0.28f), Color.Transparent), b, r), r, b)
            if (p.arches) drawRome(p.accent)
        }
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(0.4f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrismText(text = stringResource(R.string.app_name), size = 18.sp, weight = FontWeight.Bold, maxLines = 1)
                PrismText(text = stringResource(R.string.built_by), size = 11.sp, color = p.accent, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                MockButton(stringResource(R.string.action_check_updates), p, focused = true)
                MockButton(stringResource(R.string.action_add_url), p, focused = false)
                MockButton(stringResource(R.string.action_pair), p, focused = false)
            }
            Column(
                Modifier.weight(0.6f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrismText(text = stringResource(R.string.playlists_title), size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                MockButton("Live TV", p, focused = false, sub = stringResource(R.string.status_ok, 1240), subColor = PrismColors.Ok)
                MockButton("Film", p, focused = false, sub = stringResource(R.string.status_ok, 5300), subColor = PrismColors.Ok)
            }
        }
    }
}

@Composable
private fun MockButton(text: String, p: Palette, focused: Boolean, sub: String? = null, subColor: Color = PrismColors.TextSecondary) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (focused) Brush.linearGradient(listOf(p.accent.copy(alpha = 0.55f), p.accentAlt.copy(alpha = 0.45f)))
                else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))),
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.22f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        PrismText(text = text, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        if (sub != null) PrismText(text = sub, size = 10.sp, color = subColor, maxLines = 1)
    }
}
