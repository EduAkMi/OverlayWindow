package com.overlaywindow.overlay.voice.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.overlaywindow.R
import com.overlaywindow.overlay.voice.domain.VoiceAction
import com.overlaywindow.overlay.voice.domain.VoiceCommandInterpreter
import com.overlaywindow.overlay.voice.domain.VoiceDestination
import com.overlaywindow.overlay.voice.domain.VoiceStatus
import com.overlaywindow.ui.preview.PreviewTheme
import com.overlaywindow.ui.preview.ThemePreviews
import java.util.Locale

private object VoiceRoutes {
    const val CONVERSATION = "conversation"
    const val SETTINGS = "settings"

    fun of(destination: VoiceDestination) = when (destination) {
        VoiceDestination.CONVERSATION -> CONVERSATION
        VoiceDestination.SETTINGS -> SETTINGS
    }
}

/**
 * Raiz do overlay de voz. Expandido ocupa a largura toda e tem navegação própria
 * (NavHost); recolhido vira uma pílula pequena que continua ouvindo.
 *
 * O tamanho/visibilidade da *janela* é controlado pelo VoiceOverlayService a partir do
 * [OverlayMode] — no modo escondido esta composição nem existe.
 *
 * @param onStop encerra o serviço (desliga o microfone).
 */
@Composable
fun VoiceOverlayRoot(
    viewModel: VoiceOverlayViewModel,
    onStop: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    LaunchedEffect(viewModel, navController) {
        viewModel.effects.collect { effect ->
            if (effect !is VoiceOverlayEffect.Perform) return@collect
            when (val action = effect.action) {
                is VoiceAction.Navigate -> navController.navigateSingle(VoiceRoutes.of(action.destination))
                VoiceAction.Back ->
                    if (!navController.popBackStack()) viewModel.onCollapse()
                VoiceAction.Close -> onStop()
                else -> Unit
            }
        }
    }

    when (uiState.mode) {
        OverlayMode.HIDDEN -> return
        OverlayMode.COLLAPSED -> {
            CollapsedPill(uiState, onExpand = viewModel::onExpand)
            return
        }
        OverlayMode.EXPANDED -> Unit
    }

    VoiceExpandedPanel(
        uiState = uiState,
        onCollapse = viewModel::onCollapse,
        onHide = viewModel::onHide,
        onStop = onStop,
    ) {
        NavHost(navController = navController, startDestination = VoiceRoutes.CONVERSATION) {
            composable(VoiceRoutes.CONVERSATION) {
                ConversationScreen(
                    uiState = uiState,
                    onMicClick = viewModel::onMicClick,
                    onSubmit = viewModel::onTextSubmitted,
                    onOpenSettings = { navController.navigateSingle(VoiceRoutes.SETTINGS) },
                )
            }
            composable(VoiceRoutes.SETTINGS) {
                SettingsScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onContinuousChanged = viewModel::onContinuousChanged,
                    onSpeechRateChanged = viewModel::onSpeechRateChanged,
                )
            }
        }
    }
}

/** Moldura do painel expandido (cabeçalho + conteúdo). Sem estado próprio → previewável. */
@Composable
private fun VoiceExpandedPanel(
    uiState: VoiceOverlayUiState,
    onCollapse: () -> Unit,
    onHide: () -> Unit,
    onStop: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Header(uiState = uiState, onCollapse = onCollapse, onHide = onHide, onStop = onStop)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun NavHostController.navigateSingle(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(VoiceRoutes.CONVERSATION)
    }
}

@Composable
private fun Header(
    uiState: VoiceOverlayUiState,
    onCollapse: () -> Unit,
    onHide: () -> Unit,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MicIndicator(uiState.status, uiState.micLevel)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text("Assistente em overlay", style = MaterialTheme.typography.titleMedium)
            Text(
                uiState.statusLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Minimizar")
        }
        // Fechar = esconder: a janela some, mas o microfone segue ouvindo a frase de ativação.
        IconButton(onClick = onHide) {
            Icon(Icons.Filled.Close, contentDescription = "Esconder (continua ouvindo)")
        }
        IconButton(onClick = onStop) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Encerrar (desliga o microfone)")
        }
    }
    val level by animateFloatAsState(uiState.micLevel, label = "micLevel")
    LinearProgressIndicator(
        progress = { if (uiState.status == VoiceStatus.LISTENING) level else 0f },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun ConversationScreen(
    uiState: VoiceOverlayUiState,
    onMicClick: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.lastIndex)
    }
    var input by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            if (uiState.messages.isEmpty()) {
                item {
                    Text(
                        "Fale algo ou digite abaixo. Experimente \"abrir configurações\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(uiState.messages) { MessageBubble(it) }
        }

        if (uiState.partialText.isNotEmpty()) {
            Text(
                "“${uiState.partialText}”",
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // O TextField só abre o teclado porque a janela fica focável enquanto expandida.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Digite para o TTS responder") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSubmit(input)
                    input = ""
                }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                onSubmit(input)
                input = ""
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = onMicClick,
                enabled = uiState.status != VoiceStatus.UNAVAILABLE,
                colors = if (uiState.status == VoiceStatus.LISTENING) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Ouvir")
            }
            Text(
                if (uiState.status == VoiceStatus.LISTENING) "Toque para parar" else "Toque para falar",
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            TextButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Text("Ajustes", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            message.text,
            color = if (message.fromUser) colors.onPrimaryContainer else colors.onSecondaryContainer,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (message.fromUser) colors.primaryContainer else colors.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SettingsScreen(
    uiState: VoiceOverlayUiState,
    onBack: () -> Unit,
    onContinuousChanged: (Boolean) -> Unit,
    onSpeechRateChanged: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Text("Ajustes de voz", style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Escuta contínua")
                Text(
                    "Reinicia o reconhecimento após cada resposta/silêncio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = uiState.continuousListening, onCheckedChange = onContinuousChanged)
        }
        Text("Velocidade da fala: ${String.format(Locale.ROOT, "%.2f", uiState.speechRate)}x")
        Slider(
            value = uiState.speechRate,
            onValueChange = onSpeechRateChanged,
            valueRange = 0.5f..2f,
            steps = 5,
        )
        HorizontalDivider()
        Text("Fechar × encerrar", style = MaterialTheme.typography.titleSmall)
        Text(
            "O X esconde a janela, mas o microfone continua ativo: diga " +
                "\"${VoiceCommandInterpreter.WAKE_PHRASE}\" para ela voltar. O botão de sair " +
                "encerra o serviço — aí só reabrindo pelo app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("Comandos de voz", style = MaterialTheme.typography.titleSmall)
        VoiceCommandInterpreter.EXAMPLES.forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Versão recolhida: pequena, não focável, mas o microfone segue ativo. */
@Composable
private fun CollapsedPill(uiState: VoiceOverlayUiState, onExpand: () -> Unit) {
    Surface(
        onClick = onExpand,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
        modifier = Modifier.padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            MicIndicator(uiState.status, uiState.micLevel, size = 28)
            Text(
                uiState.statusLabel(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Círculo que "pulsa" com o nível do microfone. */
@Composable
private fun MicIndicator(status: VoiceStatus, level: Float, size: Int = 40) {
    val scale by animateFloatAsState(
        targetValue = if (status == VoiceStatus.LISTENING) 1f + level * 0.35f else 1f,
        label = "micScale",
    )
    val color = when (status) {
        VoiceStatus.LISTENING -> MaterialTheme.colorScheme.error
        VoiceStatus.SPEAKING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color),
    ) {
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size((size * 0.55f).dp),
        )
    }
}

private fun VoiceOverlayUiState.statusLabel() =
    if (micPaused) "Microfone pausado" else status.label()

private fun VoiceStatus.label() = when (this) {
    VoiceStatus.IDLE -> "Aguardando"
    VoiceStatus.LISTENING -> "Ouvindo…"
    VoiceStatus.SPEAKING -> "Falando…"
    VoiceStatus.UNAVAILABLE -> "Reconhecimento indisponível"
}

// ---------------------------------------------------------------------------------------------
// Previews — o NavHost não roda no preview, então cada tela é exibida dentro da moldura.
// ---------------------------------------------------------------------------------------------

private val previewConversation = listOf(
    ChatMessage(fromUser = true, text = "que horas são?"),
    ChatMessage(fromUser = false, text = "Agora são 14:30."),
    ChatMessage(fromUser = true, text = "abrir configurações"),
    ChatMessage(fromUser = false, text = "Abrindo as configurações."),
)

private class VoiceStatePreviewProvider : PreviewParameterProvider<VoiceOverlayUiState> {
    override val values = sequenceOf(
        VoiceOverlayUiState(status = VoiceStatus.IDLE),
        VoiceOverlayUiState(
            status = VoiceStatus.LISTENING,
            micLevel = 0.6f,
            partialText = "abrir config",
            messages = previewConversation.take(2),
        ),
        VoiceOverlayUiState(status = VoiceStatus.SPEAKING, messages = previewConversation),
        VoiceOverlayUiState(
            status = VoiceStatus.IDLE,
            continuousListening = false,
            error = "Reconhecedor ocupado (outro app usando o microfone?).",
        ),
        VoiceOverlayUiState(status = VoiceStatus.UNAVAILABLE),
    )
}

@Preview(name = "Conversa", showBackground = true, widthDp = 400)
@Composable
private fun VoiceConversationPreview(
    @PreviewParameter(VoiceStatePreviewProvider::class) uiState: VoiceOverlayUiState,
) {
    PreviewTheme {
        VoiceExpandedPanel(uiState = uiState, onCollapse = {}, onHide = {}, onStop = {}) {
            ConversationScreen(uiState = uiState, onMicClick = {}, onSubmit = {}, onOpenSettings = {})
        }
    }
}

@ThemePreviews
@Composable
private fun VoiceSettingsPreview() {
    val uiState = VoiceOverlayUiState(speechRate = 1.25f, status = VoiceStatus.LISTENING, micLevel = 0.3f)
    PreviewTheme {
        VoiceExpandedPanel(uiState = uiState, onCollapse = {}, onHide = {}, onStop = {}) {
            SettingsScreen(uiState = uiState, onBack = {}, onContinuousChanged = {}, onSpeechRateChanged = {})
        }
    }
}

@ThemePreviews
@Composable
private fun VoiceCollapsedPreview() {
    PreviewTheme {
        Column {
            CollapsedPill(VoiceOverlayUiState(status = VoiceStatus.LISTENING, micLevel = 0.8f), onExpand = {})
            CollapsedPill(VoiceOverlayUiState(status = VoiceStatus.SPEAKING), onExpand = {})
            CollapsedPill(VoiceOverlayUiState(status = VoiceStatus.IDLE), onExpand = {})
        }
    }
}
