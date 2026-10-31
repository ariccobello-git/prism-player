package dev.red74.prismplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.red74.prismplayer.LibraryNav
import dev.red74.prismplayer.LibraryTab
import dev.red74.prismplayer.assistant.PrismBrain
import dev.red74.prismplayer.assistant.PrismKnowledge
import dev.red74.prismplayer.assistant.PrismVoice
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.Channel
import dev.red74.prismplayer.data.ContentInfo
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.EpgData
import dev.red74.prismplayer.data.SortMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Prism is doing; the figure and the light move differently for each. */
enum class PrismMood { IDLE, TALKING, LISTENING, THINKING }

/**
 * One conversation with Prism, shared by the figure in the top bar (which keeps the cursor) and the window in the
 * middle of the screen. Modelled on a voice assistant: a short greeting, it listens at once, the question stays on
 * screen, a quick "Ho capito bene?" (a "no" listens again, a new request replaces the old one), then the answer, and
 * what was asked for (a channel, a film, a series) opens by itself.
 */
@Stable
class PrismSession {
    var active by mutableStateOf(false)
        internal set
    var mood by mutableStateOf(PrismMood.IDLE)
        internal set
    /** What Prism is saying (or last said). */
    var line by mutableStateOf("")
        internal set
    /** The question as heard, also while it is being spoken. */
    var question by mutableStateOf("")
        internal set
    var answer by mutableStateOf("")
        internal set
    var hint by mutableStateOf("")
        internal set
    /** The loudness of the voice being heard, 0..1, for the light under Prism. */
    var level by mutableFloatStateOf(0f)
        internal set

    internal var job: Job? = null
    internal var starts by mutableIntStateOf(0)

    /** Starts a conversation from anywhere in the library (the remote's voice key). */
    fun requestStart() {
        if (!active) starts++
    }

    fun close() {
        job?.cancel()
        job = null
        active = false
        mood = PrismMood.IDLE
        level = 0f
    }

    companion object {
        /** The remote keys that call Prism, when the TV passes them to the app instead of keeping them for its own assistant. */
        fun isVoiceKey(event: KeyEvent): Boolean =
            event.type == KeyEventType.KeyDown &&
                (event.key == Key.VoiceAssist || event.key == Key.Assist || event.key == Key.Search)
    }
}

/** Prism Player's assistant in the top bar: a little figure with a prism for a head. OK starts a conversation. */
@Composable
fun PrismAssistantButton(
    session: PrismSession,
    catalog: Catalog,
    epg: EpgData,
    onNav: (((LibraryNav) -> LibraryNav) -> Unit),
    onPlay: (items: List<Channel>, index: Int, follow: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { PrismVoice(context) }
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { session.close(); voice.release() } }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission?.complete(granted)
    }
    val knowledge = remember(catalog, epg, onNav, onPlay) { CatalogKnowledge(catalog, epg, onNav, onPlay) }

    fun start() {
        if (session.active) return
        session.active = true
        session.job = scope.launch {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                CompletableDeferred<Boolean>().also { permission = it; askPermission.launch(Manifest.permission.RECORD_AUDIO) }.await()
            converse(session, voice, knowledge, granted)
        }
    }

    // The voice key of the remote brings the cursor to Prism and starts at once.
    LaunchedEffect(session.starts) {
        if (session.starts > 0) {
            runCatching { focus.requestFocus() }
            start()
        }
    }

    // Back ends the conversation; the cursor stays on Prism.
    BackHandler(enabled = session.active) {
        voice.stop()
        session.close()
    }

    GlassSurface(
        onClick = { start() },
        // While Prism talks the arrows and OK do nothing, so the cursor stays on it; Back still closes.
        modifier = Modifier.onPreviewKeyEvent { session.active && it.key != Key.Back && it.key != Key.Escape },
        focusRequester = focus,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        focusScale = 1.08f,
        tint = if (session.active) PrismColors.Accent else null,
    ) {
        PrismFigure(session.mood, Modifier.size(width = 46.dp, height = 58.dp), level = session.level)
    }
}

private suspend fun converse(session: PrismSession, voice: PrismVoice, knowledge: PrismKnowledge, micAllowed: Boolean) {
    session.active = true
    session.question = ""
    session.answer = ""
    session.hint = ""
    session.line = ""
    suspend fun say(text: String) {
        session.line = text
        session.mood = PrismMood.TALKING
        voice.speak(text)
        session.mood = PrismMood.IDLE
    }
    suspend fun hear(hint: String, onPartial: (String) -> Unit = {}): PrismVoice.Heard {
        session.hint = hint
        session.mood = PrismMood.LISTENING
        UiSounds.play(UiSounds.Kind.SELECT)
        val heard = voice.listen(onLevel = { session.level = it }, onPartial = onPartial)
        session.level = 0f
        session.hint = ""
        session.mood = PrismMood.IDLE
        return heard
    }
    try {
        say("Ciao! Come posso aiutarti?")
        if (!micAllowed) {
            say("Per ascoltarti mi serve il permesso del microfono. Riprova e scegli Consenti.")
            delay(1_200)
            return
        }
        var request: String? = null
        var tries = 0
        while (tries < MAX_TRIES) {
            tries++
            if (request == null) {
                session.question = ""
                session.answer = ""
                when (val heard = hear("Ti ascolto…") { partial -> session.question = partial }) {
                    PrismVoice.Heard.Unavailable -> {
                        say("Il microfono del telecomando non risponde. Riprova tra un attimo.")
                        delay(1_000)
                        return
                    }
                    PrismVoice.Heard.NoPermission -> {
                        say("Per ascoltarti mi serve il permesso del microfono.")
                        delay(1_000)
                        return
                    }
                    PrismVoice.Heard.Nothing -> {
                        if (tries < MAX_TRIES) say("Non ti ho sentito, ripeti pure.") else say("Non ti ho sentito. A presto!")
                        continue
                    }
                    is PrismVoice.Heard.Words -> request = heard.text
                }
            }
            val asked = request ?: continue
            session.question = asked
            // A moment to read the question again, then a short confirmation.
            delay(PrismVoice.glanceMs(asked))
            when (val confirmed = confirm(session, voice, { h, p -> hear(h, p) }) { say(it) }) {
                PrismBrain.Confirmation.Yes -> Unit
                is PrismBrain.Confirmation.Instead -> {
                    request = confirmed.text
                    continue
                }
                else -> {
                    request = null
                    if (tries < MAX_TRIES) say("Va bene, dimmi pure.") else say("Va bene, chiamami quando vuoi.")
                    continue
                }
            }
            session.mood = PrismMood.THINKING
            val reply = runCatching { PrismBrain.answer(asked, knowledge) }
                .getOrDefault(dev.red74.prismplayer.assistant.PrismReply("Scusa, qualcosa non ha funzionato mentre cercavo."))
            session.answer = reply.text
            say(reply.text)
            if (reply.action != null) {
                delay(300)
                session.active = false
                reply.action.invoke()
                return
            }
            delay(PrismVoice.glanceMs(reply.text) + 800)
            return
        }
        delay(900)
    } finally {
        voice.stop()
        session.level = 0f
        session.mood = PrismMood.IDLE
        session.active = false
    }
}

/** Asks to confirm aloud; once more when the answer is unclear. A new request instead of a yes or no replaces the old one. */
private suspend fun confirm(
    session: PrismSession,
    voice: PrismVoice,
    hear: suspend (String, (String) -> Unit) -> PrismVoice.Heard,
    say: suspend (String) -> Unit,
): PrismBrain.Confirmation {
    repeat(2) { attempt ->
        say(if (attempt == 0) "Ho capito bene?" else "Scusa, dimmi sì o no.")
        val heard = hear("Sì o no?") { }
        when (heard) {
            is PrismVoice.Heard.Words -> {
                val answer = PrismBrain.confirmation(heard.text)
                if (answer != PrismBrain.Confirmation.Unclear) return answer
            }
            PrismVoice.Heard.NoPermission -> return PrismBrain.Confirmation.Unclear
            else -> Unit
        }
    }
    voice.stop()
    session.hint = ""
    return PrismBrain.Confirmation.Unclear
}

private const val MAX_TRIES = 3

/** The window in the middle of the screen during a conversation: always dark enough for the text to read clearly. */
@Composable
fun PrismAssistantWindow(session: PrismSession) {
    AnimatedVisibility(
        visible = session.active,
        enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.94f),
        exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.96f),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(30.dp)
            Column(
                Modifier
                    .widthIn(max = 1000.dp)
                    .padding(24.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xF00C1226), Color(0xF0080B18))),
                        shape,
                    )
                    .border(1.5.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), PrismColors.AccentAlt.copy(alpha = 0.35f))), shape),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.padding(start = 28.dp, end = 32.dp, top = 26.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrismFigure(session.mood, Modifier.size(width = 140.dp, height = 180.dp), level = session.level)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (session.line.isNotEmpty()) {
                            PrismText(text = session.line, size = 22.sp, color = Color(0xFFBFD0FF), weight = FontWeight.Bold)
                        }
                        if (session.question.isNotEmpty()) {
                            PrismText(text = "«" + session.question + "»", size = 30.sp, color = Color.White, weight = FontWeight.Bold)
                        }
                        if (session.hint.isNotEmpty()) {
                            PrismText(text = session.hint, size = 18.sp, color = PrismColors.TextSecondary)
                        }
                        if (session.answer.isNotEmpty() && session.answer != session.line) {
                            PrismText(text = session.answer, size = 21.sp, color = Color.White)
                        }
                    }
                }
                PrismLight(session.mood, session.level, Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 30.dp))
                Box(Modifier.height(18.dp))
            }
        }
    }
}

/**
 * The light bar under Prism, like the ring of a smart speaker: the spectrum of the prism, alive while Prism listens
 * (it swells with the voice), running while it thinks, breathing while it talks.
 */
@Composable
private fun PrismLight(mood: PrismMood, level: Float, modifier: Modifier = Modifier) {
    val clock = rememberInfiniteTransition(label = "prismLight")
    val t by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart), label = "lightTime")
    val loud by animateFloatAsState(level, tween(120), label = "lightLevel")
    val on by animateFloatAsState(if (mood == PrismMood.IDLE) 0.25f else 1f, tween(350), label = "lightOn")
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        val radius = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.06f), cornerRadius = radius)
        val shift = t * w
        val colors = spectrum + spectrum
        val brush = Brush.horizontalGradient(colors, startX = -w + shift, endX = w + shift)
        val alpha = when (mood) {
            PrismMood.LISTENING -> 0.55f + 0.45f * loud
            PrismMood.TALKING -> 0.55f + 0.3f * (0.5f + 0.5f * sin(t * 2 * PI.toFloat() * 3))
            PrismMood.THINKING -> 0.8f
            PrismMood.IDLE -> 0.35f
        } * on
        val thick = when (mood) {
            PrismMood.LISTENING -> 0.45f + 0.55f * loud
            else -> 0.6f
        }
        val barH = h * thick
        if (mood == PrismMood.THINKING) {
            // A comet that runs along the bar.
            val x = ((t * 2f) % 1f) * w
            drawRoundRect(brush, Offset(0f, (h - barH) / 2), Size(w, barH), radius, alpha = 0.25f)
            drawRoundRect(brush, Offset((x - w * 0.12f).coerceAtLeast(0f), (h - barH) / 2), Size(w * 0.24f, barH), radius, alpha = alpha)
        } else {
            drawRoundRect(brush, Offset(0f, (h - barH) / 2), Size(w, barH), radius, alpha = alpha)
        }
    }
}

private val spectrum = listOf(
    Color(0xFFFF5A5A), Color(0xFFFFA24D), Color(0xFFFFE45A), Color(0xFF63F28A), Color(0xFF4DB8FF), Color(0xFF8C6BFF),
)

/**
 * Prism: a figure drawn with smooth lines and a glass prism for a head, with a spectrum shining through it, glossy
 * eyes and a mouth. Moods blend into each other: it breathes and blinks when idle, gestures and talks, raises a hand
 * to its ear and glows while listening (more when the voice is louder), and taps its chin while thinking.
 */
@Composable
fun PrismFigure(mood: PrismMood, modifier: Modifier = Modifier, level: Float = 0f) {
    val clock = rememberInfiniteTransition(label = "prism")
    val t by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "prismTime",
    )
    val talk by animateFloatAsState(if (mood == PrismMood.TALKING) 1f else 0f, tween(380), label = "talk")
    val listen by animateFloatAsState(if (mood == PrismMood.LISTENING) 1f else 0f, tween(420), label = "listen")
    val think by animateFloatAsState(if (mood == PrismMood.THINKING) 1f else 0f, tween(420), label = "think")
    val loud by animateFloatAsState(level, tween(140), label = "loud")
    Canvas(modifier) { drawPrismFigure(t * 8f, talk, listen, think, loud) }
}

private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

private fun DrawScope.drawPrismFigure(seconds: Float, talk: Float, listen: Float, think: Float, loud: Float) {
    val w = size.width
    val h = size.height
    val tau = 2 * PI.toFloat()
    val line = w * 0.06f
    val bob = sin(seconds * tau * 0.45f) * h * 0.007f + talk * sin(seconds * tau * 2.1f) * h * 0.01f
    val ink = Brush.linearGradient(listOf(Color.White, Color(0xFFBFD0FF), PrismColors.AccentAlt), Offset(0f, 0f), Offset(w, h))

    // Floor shadow.
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(w * 0.22f, h * 0.94f), Size(w * 0.56f, h * 0.045f))

    // Halo behind the head, stronger while listening (and with the voice).
    val cx = w * 0.5f
    val top = h * 0.05f + bob
    val headH = h * 0.38f
    val headCenter = Offset(cx, top + headH * 0.62f)
    val glow = 0.16f + listen * (0.22f + 0.3f * loud) + think * 0.12f * (0.5f + 0.5f * sin(seconds * tau * 1.5f))
    drawCircle(
        Brush.radialGradient(listOf(PrismColors.Accent.copy(alpha = glow), Color.Transparent), headCenter, headH * 0.95f),
        radius = headH * 0.95f,
        center = headCenter,
    )

    // Head: a glass prism, the front triangle with rounded corners and a side face behind it.
    val half = headH * 0.64f
    val apex = Offset(cx, top)
    val left = Offset(cx - half, top + headH)
    val right = Offset(cx + half, top + headH)
    val depth = Offset(w * 0.08f, -h * 0.035f)
    val side = Path().apply {
        moveTo(apex.x, apex.y); lineTo(apex.x + depth.x, apex.y + depth.y)
        lineTo(right.x + depth.x, right.y + depth.y); lineTo(right.x, right.y); close()
    }
    drawPath(side, Brush.linearGradient(listOf(PrismColors.AccentAlt.copy(alpha = 0.35f), PrismColors.Accent.copy(alpha = 0.12f)), apex, right))
    drawPath(side, Color.White.copy(alpha = 0.35f), style = Stroke(line * 0.4f, join = StrokeJoin.Round))
    val face = Path().apply { moveTo(apex.x, apex.y); lineTo(right.x, right.y); lineTo(left.x, left.y); close() }
    drawPath(
        face,
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color(0xFF9DB4FF).copy(alpha = 0.35f), PrismColors.AccentAlt.copy(alpha = 0.30f)),
            apex, Offset(cx, top + headH),
        ),
    )
    // The beam: white light in from the left, a spectrum out to the right that shimmers.
    val beamY = top + headH * 0.66f
    val out = Offset(cx + half * 0.32f, beamY)
    drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - half * 1.05f, beamY - h * 0.02f), out, line * 0.32f, StrokeCap.Round)
    spectrum.forEachIndexed { i, c ->
        val spread = (i - 2.5f) * h * (0.011f + 0.002f * sin(seconds * tau * 0.7f + i))
        drawLine(c.copy(alpha = 0.9f), out, Offset(cx + half * 1.15f, beamY + spread + h * 0.025f), line * 0.26f, StrokeCap.Round)
    }
    drawPath(face, ink, style = Stroke(line * 0.75f, join = StrokeJoin.Round))
    // A glint on the top edge.
    drawLine(Color.White.copy(alpha = 0.9f), Offset(cx - half * 0.12f, top + headH * 0.2f), Offset(cx - half * 0.32f, top + headH * 0.5f), line * 0.3f, StrokeCap.Round)

    // Eyes that blink and look around a little; a mouth that smiles and talks.
    val eyeY = top + headH * 0.47f
    val cycle = seconds % 3.7f
    val blink = if (cycle in 3.55f..3.68f) 0.12f else 1f
    val look = sin(seconds * tau * 0.13f) * line * 0.25f * (1f - talk)
    for (dx in listOf(-0.2f, 0.2f)) {
        val c = Offset(cx + half * dx + look, eyeY)
        drawOval(Color.White, Offset(c.x - line * 0.62f, c.y - line * 0.72f * blink), Size(line * 1.24f, line * 1.44f * blink))
        if (blink > 0.5f) {
            drawCircle(Color(0xFF1C2140), line * 0.38f, Offset(c.x + look * 0.3f, c.y + line * 0.1f))
            drawCircle(Color.White, line * 0.13f, Offset(c.x + look * 0.3f + line * 0.14f, c.y - line * 0.1f))
        }
    }
    val mouthY = top + headH * 0.8f
    val open = talk * (0.5f + 0.5f * sin(seconds * tau * 5.2f)) * line * 1.1f
    if (open > line * 0.15f) {
        drawOval(Color(0xFF2A1F4A), Offset(cx - line * 0.8f, mouthY - open / 2), Size(line * 1.6f, open))
        drawOval(Color(0xFFFF7C9A).copy(alpha = 0.8f), Offset(cx - line * 0.45f, mouthY + open * 0.05f), Size(line * 0.9f, open * 0.45f))
    } else {
        val smile = Path().apply {
            moveTo(cx - line * 0.9f, mouthY - line * 0.1f)
            quadraticBezierTo(cx, mouthY + line * 0.75f, cx + line * 0.9f, mouthY - line * 0.1f)
        }
        drawPath(smile, Color.White.copy(alpha = 0.95f), style = Stroke(line * 0.35f, cap = StrokeCap.Round))
    }

    // Body: a soft curve from the neck to the hips.
    val neck = Offset(cx, top + headH + h * 0.02f)
    val hips = Offset(cx, h * 0.71f + bob * 0.4f)
    val sway = sin(seconds * tau * 0.45f) * w * 0.015f + talk * sin(seconds * tau * 1.1f) * w * 0.02f
    val body = Path().apply {
        moveTo(neck.x, neck.y)
        quadraticBezierTo(cx + sway, (neck.y + hips.y) / 2, hips.x, hips.y)
    }
    drawPath(body, ink, style = Stroke(line, cap = StrokeCap.Round))

    fun limb(from: Offset, angle: Float, length: Float, bend: Float, hand: Boolean) {
        val elbow = Offset(from.x + cos(angle) * length * 0.52f, from.y + sin(angle) * length * 0.52f)
        val a2 = angle + bend
        val end = Offset(elbow.x + cos(a2) * length * 0.5f, elbow.y + sin(a2) * length * 0.5f)
        val p = Path().apply {
            moveTo(from.x, from.y)
            quadraticBezierTo(elbow.x, elbow.y, (elbow.x + end.x) / 2, (elbow.y + end.y) / 2)
            lineTo(end.x, end.y)
        }
        drawPath(p, ink, style = Stroke(line * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (hand) {
            drawCircle(Color.White.copy(alpha = 0.35f), line * 1.25f, end)
            drawCircle(Color.White, line * 0.72f, end)
        } else {
            drawLine(ink, end, Offset(end.x + (if (end.x < cx) -1f else 1f) * line * 1.3f, end.y), line * 0.9f, StrokeCap.Round)
        }
    }
    val shoulder = Offset(cx + sway * 0.3f, neck.y + h * 0.06f)
    val arm = h * 0.31f
    val down = PI.toFloat() / 2
    val idleL = down + 0.42f + 0.05f * sin(seconds * tau * 0.45f)
    val idleR = down - 0.42f - 0.05f * sin(seconds * tau * 0.45f)
    val talkL = down + 0.75f + 0.35f * sin(seconds * tau * 1.4f)
    val talkR = down - 1.05f - 0.4f * sin(seconds * tau * 1.4f + 1.2f)
    val listenR = down - 2.45f // hand up to the ear
    val thinkR = down - 2.0f + 0.08f * sin(seconds * tau * 2f) // hand to the chin
    var leftAngle = mix(idleL, talkL, talk)
    leftAngle = mix(leftAngle, down + 0.5f, listen)
    leftAngle = mix(leftAngle, down + 0.35f, think)
    var rightAngle = mix(idleR, talkR, talk)
    rightAngle = mix(rightAngle, listenR, listen)
    rightAngle = mix(rightAngle, thinkR, think)
    val rightBend = mix(mix(-0.15f, -0.7f, talk), -1.3f, maxOf(listen, think))
    limb(shoulder, leftAngle, arm, mix(0.15f, -0.45f, talk), hand = true)
    limb(shoulder, rightAngle, arm, rightBend, hand = true)
    val step = talk * 0.05f * sin(seconds * tau * 1.1f)
    limb(hips, down + 0.3f + step, h * 0.25f, -0.1f, hand = false)
    limb(hips, down - 0.3f + step, h * 0.25f, 0.1f, hand = false)

    // A few sparkles around the head while Prism talks or listens.
    val sparkle = maxOf(talk, listen)
    if (sparkle > 0.05f) {
        for (i in 0 until 4) {
            val a = seconds * tau * 0.25f + i * tau / 4
            val r = headH * (0.85f + 0.08f * sin(seconds * tau + i))
            val p = Offset(headCenter.x + cos(a) * r, headCenter.y + sin(a) * r * 0.7f)
            val twinkle = 0.5f + 0.5f * sin(seconds * tau * 2f + i * 1.7f)
            drawCircle(spectrum[(i * 2) % spectrum.size].copy(alpha = sparkle * twinkle * 0.9f), line * 0.32f * (0.6f + twinkle), p)
        }
    }
}

/** Prism's view of the open list: searches the list database, counts entries, asks the guide and the panel, opens things. */
private class CatalogKnowledge(
    private val catalog: Catalog,
    private val epg: EpgData,
    private val onNav: (((LibraryNav) -> LibraryNav) -> Unit),
    private val onPlay: (items: List<Channel>, index: Int, follow: Boolean) -> Unit,
) : PrismKnowledge {

    /** The query as said, then without its last or first word, then its longest words alone: the remote mishears. */
    private fun spellings(query: String): List<String> {
        val words = query.split(' ').filter { it.isNotEmpty() }
        val out = LinkedHashSet<String>()
        out += query
        if (words.size > 1) {
            out += words.dropLast(1).joinToString(" ")
            out += words.drop(1).joinToString(" ")
        }
        words.filter { it.length >= 4 }.sortedByDescending { it.length }.take(2).forEach { out += it }
        return out.toList()
    }

    private fun <T> firstFound(query: String, search: (String) -> List<T>): List<T> {
        for (q in spellings(query)) {
            val found = runCatching { search(q) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    override suspend fun find(kind: PrismKnowledge.Kind, query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        when (kind) {
            PrismKnowledge.Kind.FILM -> firstFound(query) { catalog.search(ContentType.MOVIE, it, limit) }.map { it.name }
            PrismKnowledge.Kind.SERIES -> firstFound(query) { catalog.searchShows(it, limit) }.map { it.title }
            PrismKnowledge.Kind.CHANNEL -> firstFound(query) { catalog.search(ContentType.LIVE, it, limit) }.map { it.name }
        }.filter { it.isNotBlank() }.distinct()
    }

    override suspend fun best(kind: PrismKnowledge.Kind?, query: String): PrismKnowledge.Hit? = withContext(Dispatchers.IO) {
        val order = kind?.let { listOf(it) }
            ?: listOf(PrismKnowledge.Kind.CHANNEL, PrismKnowledge.Kind.FILM, PrismKnowledge.Kind.SERIES)
        // The exact spelling in every kind first, so "metti Matrix" does not open a channel with a similar name.
        for (exact in listOf(true, false)) {
            for (k in order) {
                val hit = if (exact) hit(k, query, listOf(query)) else hit(k, query, spellings(query))
                if (hit != null) return@withContext hit
            }
        }
        null
    }

    private fun hit(kind: PrismKnowledge.Kind, query: String, tries: List<String>): PrismKnowledge.Hit? {
        for (q in tries) {
            when (kind) {
                PrismKnowledge.Kind.CHANNEL -> {
                    val found = runCatching { catalog.search(ContentType.LIVE, q, 40) }.getOrDefault(emptyList())
                    val channel = closest(found, query) { it.name } ?: continue
                    return PrismKnowledge.Hit(channel.name, kind) { onPlay(found, found.indexOf(channel).coerceAtLeast(0), false) }
                }
                PrismKnowledge.Kind.FILM -> {
                    val found = runCatching { catalog.search(ContentType.MOVIE, q, 20) }.getOrDefault(emptyList())
                    val film = closest(found, query) { it.name } ?: continue
                    return PrismKnowledge.Hit(film.name, kind) { onPlay(listOf(film), 0, false) }
                }
                PrismKnowledge.Kind.SERIES -> {
                    val found = runCatching { catalog.searchShows(q, 20) }.getOrDefault(emptyList())
                    val show = closest(found, query) { it.title } ?: continue
                    return PrismKnowledge.Hit(show.title, kind) {
                        onNav {
                            it.copy(
                                tab = LibraryTab.SERIES, showTitle = show.title, showGroup = show.group, season = -1, query = "",
                                movieUrl = null, origin = it.copy(resume = true, origin = null),
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    /** The entry whose name is closest to what was said: "Rai 1" before "Rai 1 HD" before "Rai 12". */
    private fun <T> closest(items: List<T>, query: String, name: (T) -> String): T? {
        val wanted = PrismBrain.normalize(query)
        return items.minByOrNull { item ->
            val n = PrismBrain.normalize(name(item)).replace(Regex("""^[a-z]{2,3}\s"""), "") // "it rai 1" -> "rai 1"
            when {
                n == wanted -> 0
                n.startsWith("$wanted ") -> 1
                Regex("""\b${Regex.escape(wanted)}\b""").containsMatchIn(n) -> 2
                else -> 3
            } * 1000 + n.length
        }
    }

    override suspend fun count(kind: PrismKnowledge.Kind): Int = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                PrismKnowledge.Kind.FILM -> catalog.movies.sumOf { it.items.size }
                PrismKnowledge.Kind.SERIES -> catalog.series.sumOf { it.shows.size }
                PrismKnowledge.Kind.CHANNEL -> catalog.live.sumOf { it.items.size }
            }
        }.getOrDefault(0)
    }

    override suspend fun group(kind: PrismKnowledge.Kind?, stems: List<String>): PrismKnowledge.Group? = withContext(Dispatchers.IO) {
        fun matches(name: String) = name.lowercase().let { n -> stems.any { n.contains(it) } }
        val order = kind?.let { listOf(it) } ?: listOf(PrismKnowledge.Kind.FILM, PrismKnowledge.Kind.SERIES, PrismKnowledge.Kind.CHANNEL)
        for (k in order) {
            runCatching {
                when (k) {
                    PrismKnowledge.Kind.FILM -> catalog.movies.filter { matches(it.name) }.maxByOrNull { it.items.size }
                        ?.let { PrismKnowledge.Group(it.name, k, it.items.size, it.items.take(4).map { c -> c.name }) }
                    PrismKnowledge.Kind.CHANNEL -> catalog.live.filter { matches(it.name) }.maxByOrNull { it.items.size }
                        ?.let { PrismKnowledge.Group(it.name, k, it.items.size, it.items.take(4).map { c -> c.name }) }
                    PrismKnowledge.Kind.SERIES -> catalog.series.filter { matches(it.name) }.maxByOrNull { it.shows.size }
                        ?.let { PrismKnowledge.Group(it.name, k, it.shows.size, it.shows.take(4).map { s -> s.title }) }
                }
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    override suspend fun latest(kind: PrismKnowledge.Kind, limit: Int): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                PrismKnowledge.Kind.FILM -> catalog.sorted(catalog.channels(ContentType.MOVIE, catalog.movies.indices.toList()), SortMode.RECENT)
                    .take(limit).map { it.name }
                PrismKnowledge.Kind.CHANNEL -> catalog.sorted(catalog.allLive(), SortMode.RECENT).take(limit).map { it.name }
                PrismKnowledge.Kind.SERIES -> catalog.sortedShows(catalog.shows(catalog.series.indices.toList()), SortMode.RECENT)
                    .take(limit).map { it.title }
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    override suspend fun onAir(query: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val found = firstFound(query) { catalog.search(ContentType.LIVE, it, 40) }
        val channel = closest(found, query) { it.name } ?: return@withContext null
        channel.name to epg.nowNext(channel, System.currentTimeMillis()).now?.title
    }

    override suspend fun plot(query: String): Pair<String, String?>? {
        val film = withContext(Dispatchers.IO) { closest(firstFound(query) { catalog.search(ContentType.MOVIE, it, 20) }, query) { it.name } }
        if (film != null) return film.name to ContentInfo.filmPlot(film.url)
        val show = withContext(Dispatchers.IO) {
            closest(firstFound(query) { catalog.searchShows(it, 20) }, query) { it.title }?.let { it to it.episodes.firstOrNull()?.url }
        } ?: return null
        return show.first.title to ContentInfo.seriesPlot(show.second, show.first.title)
    }
}
