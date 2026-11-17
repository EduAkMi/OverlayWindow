package com.overlaywindow.overlay.voice.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class VoiceStatus { IDLE, LISTENING, SPEAKING, UNAVAILABLE }

data class VoiceEngineState(
    val status: VoiceStatus = VoiceStatus.IDLE,
    /** Nível do microfone normalizado em 0..1 (só é atualizado enquanto escuta). */
    val micLevel: Float = 0f,
    /** Transcrição parcial da fala em andamento. */
    val partialText: String = "",
)

sealed interface VoiceEvent {
    /** Frase reconhecida por completo. */
    data class Recognized(val text: String) : VoiceEvent

    /** A sessão de escuta terminou sem fala reconhecível (silêncio/timeout). */
    data object NoSpeech : VoiceEvent

    data class Error(val message: String) : VoiceEvent
}

/** Entrada (reconhecimento de fala) e saída (TTS) de voz. Deve ser usado na main thread. */
interface VoiceEngine {
    val state: StateFlow<VoiceEngineState>
    val events: Flow<VoiceEvent>

    fun startListening()
    fun stopListening()

    /** Fala o texto e suspende até terminar (ou ser interrompido). */
    suspend fun speak(text: String)
    fun stopSpeaking()

    /** 1.0 = normal. */
    fun setSpeechRate(rate: Float)

    fun release()
}
