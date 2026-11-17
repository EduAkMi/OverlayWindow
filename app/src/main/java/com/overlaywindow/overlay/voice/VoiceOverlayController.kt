package com.overlaywindow.overlay.voice

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

enum class VoiceOverlayStatus {
    /** Service parado: microfone desligado; só reabre pelo app. */
    STOPPED,

    /** Janela na tela (expandida ou recolhida). */
    VISIBLE,

    /** Janela escondida; o microfone segue ouvindo a frase de ativação. */
    HIDDEN,
}

/** API pública do overlay de voz: quem o usa não precisa saber que é um Service. */
interface VoiceOverlayController {
    val status: StateFlow<VoiceOverlayStatus>

    /** Deve ser chamado com o app em primeiro plano (exigência do FGS de microfone). */
    fun start()

    /** Reexibe a janela escondida, sem reiniciar o service. */
    fun show()

    fun stop()
}

class ServiceVoiceOverlayController(private val context: Context) : VoiceOverlayController {
    override val status: StateFlow<VoiceOverlayStatus> = VoiceOverlayService.status
    override fun start() = VoiceOverlayService.start(context)
    override fun show() = VoiceOverlayService.show(context)
    override fun stop() = VoiceOverlayService.stop(context)
}
