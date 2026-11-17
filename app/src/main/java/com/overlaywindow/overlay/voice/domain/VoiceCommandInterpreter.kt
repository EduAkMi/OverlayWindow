package com.overlaywindow.overlay.voice.domain

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Telas navegáveis dentro do overlay de voz. */
enum class VoiceDestination { CONVERSATION, SETTINGS }

/** Ação que o overlay deve executar além de responder em voz. */
sealed interface VoiceAction {
    data class Navigate(val destination: VoiceDestination) : VoiceAction
    data object Back : VoiceAction
    data object Collapse : VoiceAction

    /** Esconde a janela, mas continua ouvindo a frase de ativação. */
    data object Hide : VoiceAction

    /** Encerra o serviço: microfone desligado, não dá mais para chamar por voz. */
    data object Close : VoiceAction
    data object StopContinuousListening : VoiceAction
    data class ChangeSpeechRate(val delta: Float) : VoiceAction
}

data class VoiceReply(val speech: String, val action: VoiceAction? = null)

/**
 * Interpretador de comandos por palavras-chave. Kotlin puro (sem Android) para ser
 * facilmente testável e substituível por um NLU/LLM no futuro.
 */
class VoiceCommandInterpreter(
    private val clock: () -> Date = ::Date,
) {
    fun interpret(rawText: String): VoiceReply {
        val text = rawText.normalize()
        return when {
            text.hasAny("configurac", "ajuste") ->
                VoiceReply("Abrindo as configurações.", VoiceAction.Navigate(VoiceDestination.SETTINGS))
            text.hasAny("conversa", "inicio") ->
                VoiceReply("Voltando para a conversa.", VoiceAction.Navigate(VoiceDestination.CONVERSATION))
            text.hasAny("voltar", "volta") ->
                VoiceReply("Ok, voltando.", VoiceAction.Back)
            text.hasAny("minimiza", "recolhe") ->
                VoiceReply("Minimizando. Continuo por aqui.", VoiceAction.Collapse)
            text.hasAny("encerrar", "desligar") ->
                VoiceReply("Desligando. Até logo!", VoiceAction.Close)
            text.hasAny("fechar", "esconde", "tchau") ->
                VoiceReply("Ok, vou sumir. Diga \"$WAKE_PHRASE\" para me chamar.", VoiceAction.Hide)
            text.hasAny("parar de ouvir", "pare de ouvir", "silencio") ->
                VoiceReply("Ok, parei de ouvir.", VoiceAction.StopContinuousListening)
            text.hasAny("mais rapido") ->
                VoiceReply("Vou falar mais rápido.", VoiceAction.ChangeSpeechRate(+0.25f))
            text.hasAny("mais devagar", "mais lento") ->
                VoiceReply("Vou falar mais devagar.", VoiceAction.ChangeSpeechRate(-0.25f))
            text.hasAny("que horas", "horas sao") ->
                VoiceReply("Agora são ${SimpleDateFormat("HH:mm", Locale.ROOT).format(clock())}.")
            text.hasWord("ola", "oi") || text.hasAny("bom dia", "boa tarde", "boa noite") ->
                VoiceReply("Olá! Estou rodando numa janela flutuante sobre os outros apps.")
            else -> VoiceReply("Você disse: $rawText")
        }
    }

    /**
     * Com a janela escondida, só a frase de ativação é tratada — todo o resto é ignorado
     * em silêncio. Aceita variações como "abrir assistente" e "ei, assistente".
     */
    fun isWakePhrase(rawText: String): Boolean = rawText.normalize().hasAny(*WAKE_KEYWORDS)

    private fun String.hasAny(vararg keywords: String) = keywords.any { contains(it) }

    /** Palavra inteira — evita que "oi" case com "noite" ou "depois". */
    private fun String.hasWord(vararg words: String): Boolean {
        val tokens = split(NON_LETTERS).toSet()
        return words.any { it in tokens }
    }

    private fun String.normalize(): String =
        Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

    companion object {
        /** Exemplos exibidos na tela de configurações. */
        const val WAKE_PHRASE = "abrir assistente"

        val EXAMPLES = listOf(
            "\"abrir configurações\" / \"voltar\"",
            "\"que horas são?\"",
            "\"mais rápido\" / \"mais devagar\"",
            "\"minimizar\" / \"fechar\" (some, mas segue ouvindo)",
            "\"$WAKE_PHRASE\" (reabre depois de fechar)",
            "\"encerrar\" (desliga o microfone)",
            "\"parar de ouvir\"",
        )

        private val WAKE_KEYWORDS = arrayOf("assistente", "abrir overlay", "abrir janela")

        private val DIACRITICS = "\\p{Mn}+".toRegex()
        private val NON_LETTERS = "[^a-z0-9]+".toRegex()
    }
}
