package dev.red74.prismplayer.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
                    result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                } == true
                ready.complete(ok)
            }
        }.getOrNull()
        if (tts == null) ready.complete(false)
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

    /** Listens for one sentence; [onPartial] receives the words while they are spoken. */
    suspend fun listen(onPartial: (String) -> Unit): Heard {
        if (!canListen) return Heard.Unavailable
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()?.also { recognizer = it }
            ?: return Heard.Unavailable
        return suspendCancellableCoroutine { cont ->
            fun finish(result: Heard) {
                if (cont.isActive) cont.resume(result)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    best(partialResults)?.let { text -> main.post { onPartial(text) } }
                }
                override fun onResults(results: Bundle?) {
                    finish(best(results)?.let { Heard.Words(it) } ?: Heard.Nothing)
                }
                override fun onError(error: Int) {
                    finish(
                        when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Heard.NoPermission
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Nothing
                            else -> Heard.Unavailable
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
            runCatching { r.startListening(intent) }.onFailure { finish(Heard.Unavailable) }
            cont.invokeOnCancellation { main.post { runCatching { r.cancel() } } }
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
        /** About how long it takes to read [text] on screen. */
        fun readingTimeMs(text: String): Long = (1_200L + text.length * 55L).coerceIn(1_500L, 12_000L)
    }
}
