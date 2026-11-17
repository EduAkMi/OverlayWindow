package com.overlaywindow.overlay.voice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.overlaywindow.overlay.voice.domain.VoiceAction
import com.overlaywindow.overlay.voice.domain.VoiceCommandInterpreter
import com.overlaywindow.overlay.voice.domain.VoiceEngine
import com.overlaywindow.overlay.voice.domain.VoiceEvent
import com.overlaywindow.overlay.voice.domain.VoiceStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String)

/** Como o overlay aparece. O serviço de microfone continua rodando nos três modos. */
enum class OverlayMode {
    /** Painel em largura total, focável (teclado e "voltar"). */
    EXPANDED,

    /** Pílula pequena, não focável. */
    COLLAPSED,

    /** Sem janela na tela; só escuta a frase de ativação. */
    HIDDEN,
}

data class VoiceOverlayUiState(
    val mode: OverlayMode = OverlayMode.EXPANDED,
    val status: VoiceStatus = VoiceStatus.IDLE,
    val micLevel: Float = 0f,
    val partialText: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val continuousListening: Boolean = true,
    /**
     * Microfone pausado pelo usuário (notificação): nenhuma escuta, em nenhum modo — nem a
     * frase de ativação. O service continua vivo para poder retomar sem abrir o app.
     */
    val micPaused: Boolean = false,
    val speechRate: Float = 1f,
    val error: String? = null,
)

/** Efeitos que a UI/serviço precisam executar (navegação, encerrar o serviço...). */
sealed interface VoiceOverlayEffect {
    data class Perform(val action: VoiceAction) : VoiceOverlayEffect
}

/**
 * ViewModel do overlay de voz. Vive no ViewModelStore da *janela* (ComposeOverlayWindow), que
 * sobrevive enquanto ela está escondida — por isso o histórico e a escuta continuam no modo
 * [OverlayMode.HIDDEN]. É limpo quando o serviço é encerrado.
 */
class VoiceOverlayViewModel(
    private val engine: VoiceEngine,
    private val interpreter: VoiceCommandInterpreter,
) : ViewModel() {

    private val local = MutableStateFlow(VoiceOverlayUiState())

    val uiState: StateFlow<VoiceOverlayUiState> =
        combine(local, engine.state) { ui, voice ->
            ui.copy(status = voice.status, micLevel = voice.micLevel, partialText = voice.partialText)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    private val _effects = Channel<VoiceOverlayEffect>(Channel.BUFFERED)
    val effects: Flow<VoiceOverlayEffect> = _effects.receiveAsFlow()

    private var replyJob: Job? = null

    private val isHidden: Boolean get() = local.value.mode == OverlayMode.HIDDEN

    init {
        viewModelScope.launch { engine.events.collect(::onVoiceEvent) }
        replyJob = viewModelScope.launch {
            engine.speak("Olá! Estou ouvindo. Diga \"abrir configurações\" ou pergunte que horas são.")
            listenIfContinuous()
        }
    }

    fun onMicClick() {
        if (local.value.micPaused) {
            onResumeListening()
            return
        }
        if (uiState.value.status == VoiceStatus.LISTENING) {
            local.update { it.copy(continuousListening = false) }
            engine.stopListening()
        } else {
            // Cancela a resposta em andamento: senão, ao terminar ela reiniciaria a escuta
            // por conta própria e o reconhecedor seria iniciado duas vezes.
            replyJob?.cancel()
            local.update { it.copy(error = null) }
            engine.startListening()
        }
    }

    /** Texto digitado: demonstra teclado numa janela de overlay + TTS. */
    fun onTextSubmitted(text: String) {
        if (text.isNotBlank()) handleUtterance(text.trim())
    }

    fun onContinuousChanged(enabled: Boolean) {
        local.update { it.copy(continuousListening = enabled) }
        if (enabled && uiState.value.status == VoiceStatus.IDLE) engine.startListening()
    }

    fun onSpeechRateChanged(rate: Float) {
        local.update { it.copy(speechRate = rate) }
        engine.setSpeechRate(rate)
    }

    /** Desliga a escuta (o indicador de microfone do sistema some) sem encerrar o service. */
    fun onPauseListening() {
        replyJob?.cancel()
        local.update { it.copy(micPaused = true) }
        engine.stopListening()
        engine.stopSpeaking()
    }

    fun onResumeListening() {
        local.update { it.copy(micPaused = false, error = null) }
        if (uiState.value.status != VoiceStatus.LISTENING) engine.startListening()
    }

    fun onExpand() = setMode(OverlayMode.EXPANDED)

    fun onCollapse() = setMode(OverlayMode.COLLAPSED)

    /** Esconde a janela e garante que a escuta continue para a frase de ativação. */
    fun onHide() {
        setMode(OverlayMode.HIDDEN)
        if (uiState.value.status == VoiceStatus.IDLE) listenIfContinuous()
    }

    /** Reexibe a janela (frase de ativação, notificação ou tela principal do app). */
    fun onShow() {
        if (!isHidden) return
        setMode(OverlayMode.EXPANDED)
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            engine.speak("Oi! Estou aqui.")
            listenIfContinuous()
        }
    }

    private fun setMode(mode: OverlayMode) {
        local.update { it.copy(mode = mode, error = if (mode == OverlayMode.HIDDEN) null else it.error) }
    }

    private fun onVoiceEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Recognized -> handleUtterance(event.text)
            VoiceEvent.NoSpeech -> listenIfContinuous()
            is VoiceEvent.Error -> {
                if (isHidden) {
                    // Escondido não há UI para mostrar o erro nem botão para religar:
                    // tenta de novo após uma pausa para não entrar em loop apertado.
                    replyJob?.cancel()
                    replyJob = viewModelScope.launch {
                        delay(HIDDEN_RETRY_DELAY_MS)
                        listenIfContinuous()
                    }
                } else {
                    local.update { it.copy(error = event.message, continuousListening = false) }
                }
            }
        }
    }

    private fun handleUtterance(text: String) {
        if (isHidden) {
            // Escondido: só a frase de ativação interessa; o resto é ignorado em silêncio.
            if (interpreter.isWakePhrase(text)) {
                local.update { it.copy(messages = it.messages + ChatMessage(true, text)) }
                onShow()
            } else {
                listenIfContinuous()
            }
            return
        }

        val reply = interpreter.interpret(text)
        local.update {
            it.copy(messages = it.messages + ChatMessage(true, text) + ChatMessage(false, reply.speech))
        }
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            if (reply.action == VoiceAction.Close) {
                // Fala a despedida antes de encerrar o serviço (encerrar cancela este escopo).
                engine.speak(reply.speech)
                applyAction(VoiceAction.Close)
                return@launch
            }
            reply.action?.let { applyAction(it) }
            engine.speak(reply.speech)
            listenIfContinuous()
        }
    }

    private suspend fun applyAction(action: VoiceAction) {
        when (action) {
            is VoiceAction.ChangeSpeechRate ->
                onSpeechRateChanged((uiState.value.speechRate + action.delta).coerceIn(0.5f, 2f))
            VoiceAction.StopContinuousListening ->
                local.update { it.copy(continuousListening = false) }
            VoiceAction.Collapse -> onCollapse()
            VoiceAction.Hide -> setMode(OverlayMode.HIDDEN)
            // Navegação e encerramento dependem da UI/serviço.
            else -> _effects.send(VoiceOverlayEffect.Perform(action))
        }
    }

    /** Escondido, a escuta é sempre contínua: é o único jeito de ouvir a frase de ativação. */
    private fun listenIfContinuous() {
        val state = local.value
        if (!state.micPaused && (state.continuousListening || isHidden)) engine.startListening()
    }

    override fun onCleared() {
        engine.stopListening()
        engine.stopSpeaking()
    }

    private companion object {
        const val HIDDEN_RETRY_DELAY_MS = 3_000L
    }
}
