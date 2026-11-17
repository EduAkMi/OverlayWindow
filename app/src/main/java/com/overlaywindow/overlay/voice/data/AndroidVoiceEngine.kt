package com.overlaywindow.overlay.voice.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.overlaywindow.overlay.voice.domain.VoiceEngine
import com.overlaywindow.overlay.voice.domain.VoiceEngineState
import com.overlaywindow.overlay.voice.domain.VoiceEvent
import com.overlaywindow.overlay.voice.domain.VoiceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Implementação com as APIs da plataforma:
 * - [SpeechRecognizer]: reconhecimento + nível do microfone (onRmsChanged). Cada sessão
 *   termina sozinha após silêncio — escuta "contínua" é feita reiniciando a sessão.
 * - [TextToSpeech]: síntese de voz. Inicializa de forma assíncrona.
 *
 * Para evitar que o microfone capte a própria fala do TTS, [speak] interrompe a escuta.
 */
class AndroidVoiceEngine(context: Context) : VoiceEngine {

    private val locale: Locale = Locale.forLanguageTag("pt-BR")

    private val _state = MutableStateFlow(VoiceEngineState())
    override val state: StateFlow<VoiceEngineState> = _state.asStateFlow()

    private val _events = Channel<VoiceEvent>(Channel.BUFFERED)
    override val events: Flow<VoiceEvent> = _events.receiveAsFlow()

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(Listener()) }
        } else {
            _state.update { it.copy(status = VoiceStatus.UNAVAILABLE) }
            null
        }

    private val ttsReady = CompletableDeferred<Boolean>()
    private val pendingUtterances = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ttsReady.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                _state.update { it.copy(status = VoiceStatus.SPEAKING) }
            }

            override fun onDone(utteranceId: String) = finishUtterance(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finishUtterance(utteranceId)

            override fun onStop(utteranceId: String, interrupted: Boolean) = finishUtterance(utteranceId)
        })
    }

    override fun startListening() {
        val recognizer = recognizer ?: return
        stopSpeaking()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer.startListening(intent)
        _state.update { it.copy(status = VoiceStatus.LISTENING, partialText = "", micLevel = 0f) }
    }

    override fun stopListening() {
        recognizer?.cancel()
        _state.update {
            if (it.status == VoiceStatus.LISTENING) it.copy(status = VoiceStatus.IDLE, micLevel = 0f) else it
        }
    }

    override suspend fun speak(text: String) {
        stopListening()
        if (!ttsReady.await()) {
            _events.trySend(VoiceEvent.Error("TextToSpeech indisponível neste aparelho."))
            return
        }
        tts.language = locale
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            pendingUtterances[id] = continuation
            continuation.invokeOnCancellation {
                pendingUtterances.remove(id)
                tts.stop()
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                finishUtterance(id)
            }
        }
    }

    override fun stopSpeaking() {
        tts.stop()
    }

    override fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    override fun release() {
        recognizer?.destroy()
        tts.stop()
        tts.shutdown()
        pendingUtterances.values.forEach { it.resume(Unit) }
        pendingUtterances.clear()
        _events.close()
    }

    /** Chamado pela thread do TTS. */
    private fun finishUtterance(id: String) {
        _state.update { if (it.status == VoiceStatus.SPEAKING) it.copy(status = VoiceStatus.IDLE) else it }
        pendingUtterances.remove(id)?.let { if (it.isActive) it.resume(Unit) }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB varia aproximadamente de -2 a 10.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(micLevel = level) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            _state.update { it.copy(partialText = text) }
        }

        override fun onResults(results: Bundle?) {
            _state.update { it.copy(status = VoiceStatus.IDLE, micLevel = 0f, partialText = "") }
            val text = results.firstResult()
            _events.trySend(if (text.isNullOrBlank()) VoiceEvent.NoSpeech else VoiceEvent.Recognized(text))
        }

        override fun onError(error: Int) {
            _state.update { it.copy(status = VoiceStatus.IDLE, micLevel = 0f, partialText = "") }
            val event = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceEvent.NoSpeech
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    VoiceEvent.Error("Sem permissão de microfone.")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    VoiceEvent.Error("Reconhecedor ocupado (outro app usando o microfone?).")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceEvent.Error("Falha de rede no reconhecimento.")
                SpeechRecognizer.ERROR_CLIENT -> return // gerado pelo nosso próprio cancel()
                else -> VoiceEvent.Error("Erro de reconhecimento ($error).")
            }
            _events.trySend(event)
        }

        private fun Bundle?.firstResult(): String? =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}
