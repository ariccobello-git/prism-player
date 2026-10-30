package dev.red74.prismplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.red74.prismplayer.assistant.PrismBrain
import dev.red74.prismplayer.assistant.PrismKnowledge
import dev.red74.prismplayer.assistant.PrismVoice
import dev.red74.prismplayer.data.Catalog
import dev.red74.prismplayer.data.ContentInfo
import dev.red74.prismplayer.data.ContentType
import dev.red74.prismplayer.data.EpgData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Prism is doing; the figure moves differently for each. */
enum class PrismMood { IDLE, TALKING, LISTENING, THINKING }

/**
 * One conversation with Prism, shared by the figure in the top bar (which keeps the cursor) and the window in the
 * middle of the screen. Prism greets, asks how it can help and listens; shows what it understood and, after a
 * moment to read it, asks to confirm aloud; on "no" it listens again, on "yes" it answers and closes.
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

    internal var job: Job? = null

    fun close() {
        job?.cancel()
        job = null
        active = false
        mood = PrismMood.IDLE
    }
}

/** Prism Player's assistant in the top bar: a little figure with a prism for a head. OK starts a conversation. */
@Composable
fun PrismAssistantButton(session: PrismSession, catalog: Catalog, epg: EpgData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { PrismVoice(context) }
    DisposableEffect(Unit) { onDispose { session.close(); voice.release() } }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission?.complete(granted)
    }
    val knowledge = remember(catalog, epg) { CatalogKnowledge(catalog, epg) }

    // Back ends the conversation; the cursor stays on Prism.
    BackHandler(enabled = session.active) {
        voice.stop()
        session.close()
    }

    GlassSurface(
        onClick = {
            if (session.active) return@GlassSurface
            session.job = scope.launch {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                    CompletableDeferred<Boolean>().also { permission = it; askPermission.launch(Manifest.permission.RECORD_AUDIO) }.await()
                converse(session, voice, knowledge, granted)
            }
        },
        // While Prism talks the arrows and OK do nothing, so the cursor stays on it; Back still closes.
        modifier = Modifier.onPreviewKeyEvent { session.active && it.key != Key.Back && it.key != Key.Escape },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        focusScale = 1.08f,
    ) {
        PrismFigure(session.mood, Modifier.size(width = 44.dp, height = 56.dp))
    }
}

private suspend fun converse(session: PrismSession, voice: PrismVoice, knowledge: PrismKnowledge, micAllowed: Boolean) {
    session.active = true
    session.question = ""
    session.answer = ""
    session.hint = ""
    suspend fun say(text: String) {
        session.line = text
        session.mood = PrismMood.TALKING
        voice.speak(text)
        session.mood = PrismMood.IDLE
    }
    try {
        say("Ciao! Come posso aiutarti?")
        if (!micAllowed) {
            say("Per ascoltarti mi serve il permesso di usare il microfono. Riprova e scegli Consenti.")
            delay(1_500)
            return
        }
        var questions = 0
        while (questions < MAX_QUESTIONS) {
            questions++
            session.question = ""
            session.answer = ""
            session.hint = "Parla pure, ti ascolto…"
            session.mood = PrismMood.LISTENING
            val heard = voice.listen { partial -> session.question = partial }
            session.hint = ""
            when (heard) {
                PrismVoice.Heard.Unavailable -> {
                    say("Il microfono del telecomando o il riconoscimento vocale non sono disponibili su questa TV.")
                    delay(1_500)
                    return
                }
                PrismVoice.Heard.NoPermission -> {
                    say("Per ascoltarti mi serve il permesso di usare il microfono.")
                    delay(1_500)
                    return
                }
                PrismVoice.Heard.Nothing -> {
                    if (questions < MAX_QUESTIONS) say("Non ti ho sentito. Riprova pure.") else say("Non ti ho sentito. A presto!")
                    continue
                }
                is PrismVoice.Heard.Words -> session.question = heard.text
            }
            // Time to read what Prism understood before it asks.
            session.mood = PrismMood.IDLE
            delay(PrismVoice.readingTimeMs(session.question))
            val confirmed = confirm(session, voice) { say(it) }
            if (confirmed != true) {
                if (questions < MAX_QUESTIONS) say("Va bene, ripeti pure la domanda.") else say("Va bene, riprova quando vuoi. A presto!")
                continue
            }
            session.mood = PrismMood.THINKING
            session.hint = "Ci penso un attimo…"
            val reply = runCatching { PrismBrain.answer(session.question, knowledge) }
                .getOrDefault("Scusa, qualcosa non ha funzionato mentre cercavo la risposta.")
            session.hint = ""
            session.answer = reply
            say(reply)
            delay(2_500)
            return
        }
        delay(1_200)
    } finally {
        voice.stop()
        session.mood = PrismMood.IDLE
        session.active = false
    }
}

/** Asks to confirm aloud; a second try when the answer is neither yes nor no. Null when nothing clear came. */
private suspend fun confirm(session: PrismSession, voice: PrismVoice, say: suspend (String) -> Unit): Boolean? {
    repeat(2) { attempt ->
        say(if (attempt == 0) "Ho capito bene? Dimmi sì o no." else "Scusa, rispondi sì oppure no.")
        session.hint = "Sì o no?"
        session.mood = PrismMood.LISTENING
        val heard = voice.listen { }
        session.hint = ""
        session.mood = PrismMood.IDLE
        if (heard is PrismVoice.Heard.Words) PrismBrain.yesOrNo(heard.text)?.let { return it }
        if (heard == PrismVoice.Heard.NoPermission) return null
    }
    return null
}

private const val MAX_QUESTIONS = 3

/** The window in the middle of the screen during a conversation: always dark enough for the text to read clearly. */
@Composable
fun PrismAssistantWindow(session: PrismSession) {
    AnimatedVisibility(visible = session.active, enter = fadeIn(tween(250)), exit = fadeOut(tween(350))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(28.dp)
            Row(
                Modifier
                    .widthIn(max = 980.dp)
                    .padding(24.dp)
                    .background(Color(0xEB0A0F1E), shape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), shape)
                    .padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrismFigure(session.mood, Modifier.size(width = 130.dp, height = 170.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (session.line.isNotEmpty()) {
                        PrismText(text = session.line, size = 22.sp, color = PrismColors.Accent, weight = FontWeight.Bold)
                    }
                    if (session.question.isNotEmpty()) {
                        PrismText(text = "«" + session.question + "»", size = 28.sp, color = Color.White, weight = FontWeight.Bold)
                    }
                    if (session.hint.isNotEmpty()) {
                        PrismText(text = session.hint, size = 18.sp, color = PrismColors.TextSecondary)
                    }
                    if (session.answer.isNotEmpty() && session.answer != session.line) {
                        PrismText(text = session.answer, size = 21.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * Prism drawn with lines: a glass prism for a head (with a little spectrum through it), a line body, arms and legs.
 * It breathes when idle, gestures and moves its mouth when it talks, and its head glows while it listens.
 */
@Composable
fun PrismFigure(mood: PrismMood, modifier: Modifier = Modifier) {
    val clock = rememberInfiniteTransition(label = "prism")
    val t by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
        label = "prismTime",
    )
    Canvas(modifier) { drawPrismFigure(t * 4f, mood) }
}

private fun DrawScope.drawPrismFigure(seconds: Float, mood: PrismMood) {
    val w = size.width
    val h = size.height
    val line = w * 0.055f
    val tau = 2 * PI.toFloat()
    val talking = mood == PrismMood.TALKING
    val bob = when (mood) {
        PrismMood.TALKING -> sin(seconds * tau * 2.2f) * h * 0.012f
        PrismMood.THINKING -> sin(seconds * tau * 0.8f) * h * 0.01f
        else -> sin(seconds * tau * 0.5f) * h * 0.008f
    }
    val ink = Brush.linearGradient(listOf(Color.White, PrismColors.Accent, PrismColors.AccentAlt), Offset(0f, 0f), Offset(w, h))

    // Head: the front triangle of a prism with a side face behind it.
    val cx = w * 0.5f
    val top = h * 0.03f + bob
    val headH = h * 0.36f
    val half = headH * 0.62f
    val apex = Offset(cx, top)
    val left = Offset(cx - half, top + headH)
    val right = Offset(cx + half, top + headH)
    val depth = Offset(w * 0.07f, -h * 0.03f)
    val side = Path().apply {
        moveTo(apex.x, apex.y); lineTo(apex.x + depth.x, apex.y + depth.y)
        lineTo(right.x + depth.x, right.y + depth.y); lineTo(right.x, right.y); close()
    }
    drawPath(side, Color.White.copy(alpha = 0.10f))
    drawPath(side, Color.White.copy(alpha = 0.45f), style = Stroke(line * 0.5f))
    val face = Path().apply { moveTo(apex.x, apex.y); lineTo(right.x, right.y); lineTo(left.x, left.y); close() }
    val glow = when (mood) {
        PrismMood.LISTENING -> 0.35f + 0.25f * (0.5f + 0.5f * sin(seconds * tau * 1.5f))
        PrismMood.THINKING -> 0.25f + 0.15f * (0.5f + 0.5f * sin(seconds * tau * 3f))
        else -> 0.18f
    }
    drawCircle(PrismColors.Accent.copy(alpha = glow * 0.6f), radius = headH * 0.75f, center = Offset(cx, top + headH * 0.62f))
    drawPath(face, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f + glow * 0.3f), PrismColors.Accent.copy(alpha = 0.18f)), top, top + headH))
    // A thin beam through the glass, coming out as a spectrum.
    val beamY = top + headH * 0.62f
    val spectrum = listOf(Color(0xFFFF4D4D), Color(0xFFFFA64D), Color(0xFFFFF05A), Color(0xFF5DFF7A), Color(0xFF4DB8FF), Color(0xFF9B6BFF))
    spectrum.forEachIndexed { i, c ->
        val spread = (i - 2.5f) * h * 0.012f
        drawLine(c.copy(alpha = 0.85f), Offset(cx + half * 0.15f, beamY), Offset(cx + half * 0.85f, beamY + spread + h * 0.02f), line * 0.28f, StrokeCap.Round)
    }
    drawLine(Color.White.copy(alpha = 0.8f), Offset(cx - half * 0.85f, beamY - h * 0.015f), Offset(cx + half * 0.15f, beamY), line * 0.35f, StrokeCap.Round)
    drawPath(face, ink, style = Stroke(line * 0.8f))
    // Eyes and mouth.
    val eyeY = top + headH * 0.48f
    val blink = if ((seconds % 4f) in 3.85f..3.95f) 0.15f else 1f
    for (dx in listOf(-0.2f, 0.2f)) {
        drawOval(Color.White, Offset(cx + half * dx - line * 0.5f, eyeY - line * 0.5f * blink), androidx.compose.ui.geometry.Size(line, line * blink))
    }
    val mouthOpen = if (talking) (0.5f + 0.5f * sin(seconds * tau * 5.5f)) * line * 1.3f + line * 0.2f else line * 0.25f
    drawOval(Color.White.copy(alpha = 0.9f), Offset(cx - line * 0.9f, top + headH * 0.78f - mouthOpen / 2), androidx.compose.ui.geometry.Size(line * 1.8f, mouthOpen))

    // Body.
    val neck = Offset(cx, top + headH + h * 0.02f)
    val hips = Offset(cx, h * 0.72f + bob * 0.5f)
    drawLine(ink, neck, hips, line, StrokeCap.Round)
    val shoulder = Offset(cx, neck.y + h * 0.06f)
    fun limb(from: Offset, angle: Float, length: Float, bend: Float) {
        val elbow = Offset(from.x + cos(angle) * length * 0.5f, from.y + sin(angle) * length * 0.5f)
        val a2 = angle + bend
        val hand = Offset(elbow.x + cos(a2) * length * 0.5f, elbow.y + sin(a2) * length * 0.5f)
        drawLine(ink, from, elbow, line, StrokeCap.Round)
        drawLine(ink, elbow, hand, line, StrokeCap.Round)
        drawCircle(Color.White, line * 0.75f, hand)
    }
    val armLength = h * 0.30f
    val down = PI.toFloat() / 2
    val (leftArm, rightArm) = when (mood) {
        PrismMood.TALKING -> {
            val g = sin(seconds * tau * 1.6f)
            (down + 0.55f + 0.35f * g) to (down - 0.9f - 0.45f * sin(seconds * tau * 1.6f + 1.3f))
        }
        PrismMood.LISTENING -> (down + 0.5f) to (down - 2.2f)
        PrismMood.THINKING -> (down + 0.4f) to (down - 1.7f + 0.1f * sin(seconds * tau))
        PrismMood.IDLE -> (down + 0.38f + 0.05f * sin(seconds * tau * 0.5f)) to (down - 0.38f - 0.05f * sin(seconds * tau * 0.5f))
    }
    limb(shoulder, leftArm, armLength, if (mood == PrismMood.TALKING) -0.4f else 0.15f)
    limb(shoulder, rightArm, armLength, if (mood == PrismMood.IDLE) -0.15f else -0.9f)
    val step = if (talking) 0.06f * sin(seconds * tau * 1.1f) else 0f
    limb(hips, down + 0.32f + step, h * 0.27f, -0.12f)
    limb(hips, down - 0.32f + step, h * 0.27f, 0.12f)
}

/** Prism's view of the open list: searches the list database, counts entries, asks the guide and the panel. */
private class CatalogKnowledge(private val catalog: Catalog, private val epg: EpgData) : PrismKnowledge {
    override suspend fun find(kind: PrismKnowledge.Kind, query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
                PrismKnowledge.Kind.FILM -> catalog.search(ContentType.MOVIE, query, limit).map { it.name }
                PrismKnowledge.Kind.SERIES -> catalog.searchShows(query, limit).map { it.title }
                PrismKnowledge.Kind.CHANNEL -> catalog.search(ContentType.LIVE, query, limit).map { it.name }
            }.filter { it.isNotBlank() }.distinct()
        }.getOrDefault(emptyList())
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

    override suspend fun onAir(query: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val channel = runCatching { catalog.search(ContentType.LIVE, query, 1).firstOrNull() }.getOrNull() ?: return@withContext null
        channel.name to epg.nowNext(channel, System.currentTimeMillis()).now?.title
    }

    override suspend fun plot(query: String): Pair<String, String?>? {
        val film = withContext(Dispatchers.IO) { runCatching { catalog.search(ContentType.MOVIE, query, 1).firstOrNull() }.getOrNull() }
        if (film != null) return film.name to ContentInfo.filmPlot(film.url)
        val show = withContext(Dispatchers.IO) {
            runCatching { catalog.searchShows(query, 1).firstOrNull()?.let { it to it.episodes.firstOrNull()?.url } }.getOrNull()
        } ?: return null
        return show.first.title to ContentInfo.seriesPlot(show.second, show.first.title)
    }
}
