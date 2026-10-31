package dev.red74.prismplayer.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Prism's voice and ears: the system text-to-speech in Italian, and the system speech recogniser, which on Android
 * TV listens through the remote's microphone. Both come from the TV's own services (Google on the Xiaomi stick);
 * when one is missing, Prism still shows everything as text. Use from the main thread.
 */
class PrismVoice(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var utterance = 0

    init {
        tts = runCatching {
            TextToSpeech(app) { status ->
                val ok = status == TextToSpeech.SUCCESS && tts?.let { engine ->
                    val result = engine.setLanguage(Locale.ITALIAN)
                    val usable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                    if (usable) tune(engine)
                    usable
                } == true
                ready.complete(ok)
            }
        }.getOrNull()
        if (tts == null) ready.complete(false)
    }

    /**
     * A warmer, less robotic voice: the most natural Italian voice installed (Google's neural voices when present,
     * a male one first for a deeper tone), a slightly lower pitch and a natural pace.
     */
    private fun tune(engine: TextToSpeech) {
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        runCatching {
            val voices = engine.voices.orEmpty().filter { v ->
                v.locale.language == "it" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
            }
            VoicePick.best(voices.map { VoicePick.Option(it.name, it.quality, it.isNetworkConnectionRequired) })
                ?.let { name -> voices.firstOrNull { it.name == name } }
                ?.let { engine.voice = it }
        }
        engine.setPitch(PITCH)
        engine.setSpeechRate(RATE)
    }

    sealed interface Heard {
        data class Words(val text: String) : Heard
        data object Nothing : Heard
        data object NoPermission : Heard
        data object Unavailable : Heard
    }

    val canListen: Boolean get() = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)

    /** Says [text] and returns when it has been said (or after a reading time when there is no voice). */
    suspend fun speak(text: String) {
        val engine = tts
        val voice = withTimeoutOrNull(3_000) { ready.await() } == true && engine != null
        if (!voice) {
            delay(readingTimeMs(text))
            return
        }
        val id = "prism-" + (++utterance)
        val done = CompletableDeferred<Unit>()
        engine!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(Unit) }
        })
        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.SUCCESS
        if (!queued) {
            delay(readingTimeMs(text))
            return
        }
        try {
            // A stuck engine must not hold Prism forever.
            withTimeoutOrNull(readingTimeMs(text) * 3 + 4_000) { done.await() }
        } finally {
            if (!done.isCompleted) runCatching { engine.stop() }
        }
    }

    /**
     * Listens for one sentence; [onPartial] receives the words while they are spoken. Every listen uses a new
     * recogniser after a short pause: reusing the one that has just answered (as the confirmation right after the
     * question did) can leave it busy, and the second listen then failed. A busy or dropped recogniser is tried once more.
     */
    suspend fun listen(onLevel: (Float) -> Unit = {}, onPartial: (String) -> Unit): Heard {
        if (!canListen) return Heard.Unavailable
        var result: Heard = Heard.Unavailable
        for (attempt in 0..1) {
            delay(if (attempt == 0) 250L else 700L)
            val outcome = listenOnce(onLevel, onPartial)
            result = outcome.heard
            if (!outcome.retry) break
        }
        return result
    }

    private class Outcome(val heard: Heard, val retry: Boolean)

    private suspend fun listenOnce(onLevel: (Float) -> Unit, onPartial: (String) -> Unit): Outcome {
        runCatching { recognizer?.destroy() }
        recognizer = null
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()?.also { recognizer = it }
            ?: return Outcome(Heard.Unavailable, retry = false)
        var lastPartial: String? = null
        var lastWordsAt = 0L
        val startedAt = SystemClock.uptimeMillis()
        return suspendCancellableCoroutine { cont ->
            lateinit var watchdog: Runnable
            fun finish(result: Outcome) {
                main.removeCallbacks(watchdog)
                // The microphone is closed before Prism speaks again.
                runCatching { r.cancel() }
                if (cont.isActive) cont.resume(result)
            }
            // Short answers such as "no" sometimes come only as a partial result, with an empty or "no match" end.
            fun heardOrPartial(): Heard = lastPartial?.let { Heard.Words(it) } ?: Heard.Nothing
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                // Roughly -2..10 dB: the light under Prism follows the voice.
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    best(partialResults)?.let { text ->
                        lastPartial = text
                        lastWordsAt = SystemClock.uptimeMillis()
                        main.post { onPartial(text) }
                    }
                }
                override fun onResults(results: Bundle?) {
                    finish(Outcome(best(results)?.let { Heard.Words(it) } ?: heardOrPartial(), retry = false))
                }
                override fun onError(error: Int) {
                    finish(
                        when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Outcome(Heard.NoPermission, retry = false)
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome(heardOrPartial(), retry = false)
                            else -> lastPartial?.let { Outcome(Heard.Words(it), retry = false) } ?: Outcome(Heard.Unavailable, retry = true)
                        },
                    )
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            // On the TV the recogniser does not always send its final result when the remote's microphone closes,
            // and Prism waited for it forever: a pause after the last words ends the sentence, and so does a cap.
            watchdog = Runnable {
                val now = SystemClock.uptimeMillis()
                when {
                    lastPartial != null && now - lastWordsAt >= SILENCE_MS -> finish(Outcome(heardOrPartial(), retry = false))
                    now - startedAt >= MAX_LISTEN_MS -> finish(Outcome(heardOrPartial(), retry = false))
                    else -> main.postDelayed(watchdog, 250)
                }
            }
            main.postDelayed(watchdog, 250)
            runCatching { r.startListening(intent) }.onFailure { finish(Outcome(Heard.Unavailable, retry = true)) }
            cont.invokeOnCancellation { main.post { main.removeCallbacks(watchdog); runCatching { r.cancel() } } }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Slightly deeper and a touch quicker than the engine's default, which sounds flat and slow. */
        const val PITCH = 0.88f
        const val RATE = 1.06f

        /** Silence after the last words that ends a sentence. */
        const val SILENCE_MS = 1_600L
        const val MAX_LISTEN_MS = 15_000L

        /** About how long it takes to read [text] on screen. */
        fun readingTimeMs(text: String): Long = (1_200L + text.length * 55L).coerceIn(1_500L, 12_000L)

        /** The short pause that lets the question be read again before Prism asks to confirm it. */
        fun glanceMs(text: String): Long = (500L + text.length * 22L).coerceIn(800L, 2_200L)
    }
}

/** Chooses Prism's voice by name and quality, so the choice can be checked without a TV. */
object VoicePick {
    class Option(val name: String, val quality: Int, val needsNetwork: Boolean)

    /** Google's Italian male voices; the others are female or unknown. */
    private val male = listOf("itc", "itd")

    fun best(options: List<Option>): String? = options.maxWithOrNull(
        compareBy<Option> { it.quality }
            .thenBy { o -> if (male.any { o.name.contains("-x-$it") }) 1 else 0 }
            .thenBy { if (it.needsNetwork) 0 else 1 },
    )?.name
}
