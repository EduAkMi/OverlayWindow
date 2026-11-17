package com.overlaywindow.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.overlaywindow.data.permission.AppPermission
import com.overlaywindow.overlay.voice.VoiceOverlayStatus
import com.overlaywindow.overlay.voice.domain.VoiceCommandInterpreter
import com.overlaywindow.ui.preview.PreviewTheme

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val requestPermission = rememberPermissionRequester(onResult = viewModel::refresh)

    // A notificação é o único controle do assistente em segundo plano (pausar/encerrar):
    // se ainda não houver permissão, pede antes de iniciar — e inicia com ou sem ela.
    val notificationThenStart = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refresh()
        viewModel.onStartClick()
    }
    val notificationsGranted =
        uiState.permissions.firstOrNull { it.permission == AppPermission.NOTIFICATIONS }?.granted ?: true

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    MainContent(
        uiState = uiState,
        onRequestPermission = requestPermission,
        onStart = {
            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationThenStart.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onStartClick()
            }
        },
        onShow = viewModel::onShowClick,
        onStop = viewModel::onStopClick,
        onMessageShown = viewModel::onMessageShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(
    uiState: MainUiState,
    onRequestPermission: (AppPermission) -> Unit,
    onStart: () -> Unit,
    onShow: () -> Unit,
    onStop: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("OverlayWindow") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Assistente de voz numa janela flutuante (SYSTEM_ALERT_WINDOW) em largura total, " +
                    "com navegação interna, microfone e TTS. Ele continua sobre os outros apps " +
                    "depois que você sai deste.",
                style = MaterialTheme.typography.bodyLarge,
            )

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Permissões", style = MaterialTheme.typography.titleMedium)
                    uiState.permissions.forEach { state ->
                        PermissionRow(state, onRequest = { onRequestPermission(state.permission) })
                    }
                }
            }

            OverlayStatusCard(uiState.overlayStatus)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (uiState.overlayStatus) {
                    VoiceOverlayStatus.STOPPED ->
                        Button(onClick = onStart, enabled = uiState.canStart) { Text("Abrir overlay de voz") }
                    VoiceOverlayStatus.HIDDEN ->
                        Button(onClick = onShow) { Text("Mostrar overlay") }
                    VoiceOverlayStatus.VISIBLE ->
                        Button(onClick = {}, enabled = false) { Text("Overlay aberto") }
                }
                OutlinedButton(onClick = onStop, enabled = uiState.isRunning) { Text("Encerrar") }
            }

            Text(
                "O serviço de microfone só pode ser iniciado com este app em primeiro plano; " +
                    "depois disso o overlay segue ouvindo em background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverlayStatusCard(status: VoiceOverlayStatus) {
    val (title, detail) = when (status) {
        VoiceOverlayStatus.STOPPED ->
            "Desligado" to "Microfone desligado. Só reabre por aqui."
        VoiceOverlayStatus.VISIBLE ->
            "Na tela" to "O X do overlay esconde a janela sem desligar o microfone."
        VoiceOverlayStatus.HIDDEN ->
            "Escondido, ouvindo" to
                "Diga \"${VoiceCommandInterpreter.WAKE_PHRASE}\" para a janela voltar."
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Overlay: $title", style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(state: PermissionUiState, onRequest: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (state.granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = when {
                state.granted -> MaterialTheme.colorScheme.primary
                state.required -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.permission.label + if (state.required) "" else " (recomendada)",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                state.permission.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.granted) {
            FilledTonalButton(onClick = onRequest) { Text("Conceder") }
        }
    }
}

private val AppPermission.label: String
    get() = when (this) {
        AppPermission.DRAW_OVER_OTHER_APPS -> "Exibir sobre outros apps"
        AppPermission.MICROPHONE -> "Microfone"
        AppPermission.NOTIFICATIONS -> "Notificações"
    }

private val AppPermission.explanation: String
    get() = when (this) {
        AppPermission.DRAW_OVER_OTHER_APPS -> "SYSTEM_ALERT_WINDOW, liberada na tela de configurações."
        AppPermission.MICROPHONE -> "RECORD_AUDIO, usada pelo reconhecimento de fala."
        AppPermission.NOTIFICATIONS ->
            "Sem ela não aparece a notificação para mostrar, pausar ou encerrar o assistente em segundo plano."
    }

/**
 * Pede a permissão: diálogo do sistema para runtime permissions, tela de configurações para
 * "exibir sobre outros apps". Se a runtime permission foi negada de vez, abre os detalhes do app.
 */
@Composable
private fun rememberPermissionRequester(onResult: () -> Unit): (AppPermission) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val pending = remember { arrayOfNulls<String>(1) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onResult() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permission = pending[0]
        if (!granted && permission != null && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            settingsLauncher.launch(appDetailsIntent(context))
        }
        onResult()
    }

    return remember(context) {
        { permission ->
            when (permission) {
                AppPermission.DRAW_OVER_OTHER_APPS -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    )
                    try {
                        settingsLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        settingsLauncher.launch(appDetailsIntent(context))
                    }
                }
                AppPermission.MICROPHONE -> {
                    pending[0] = Manifest.permission.RECORD_AUDIO
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                AppPermission.NOTIFICATIONS -> {
                    // Antes do Android 13 a permissão não existe (é tratada como concedida).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pending[0] = Manifest.permission.POST_NOTIFICATIONS
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }
}

private fun appDetailsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private class MainStatePreviewProvider : PreviewParameterProvider<MainUiState> {
    private fun permissions(overlay: Boolean, mic: Boolean, notifications: Boolean) = listOf(
        PermissionUiState(AppPermission.DRAW_OVER_OTHER_APPS, overlay, required = true),
        PermissionUiState(AppPermission.MICROPHONE, mic, required = true),
        PermissionUiState(AppPermission.NOTIFICATIONS, notifications, required = false),
    )

    override val values = sequenceOf(
        MainUiState(permissions = permissions(overlay = false, mic = false, notifications = false)),
        MainUiState(permissions = permissions(overlay = true, mic = true, notifications = false)),
        MainUiState(
            permissions = permissions(overlay = true, mic = true, notifications = true),
            overlayStatus = VoiceOverlayStatus.VISIBLE,
        ),
        MainUiState(
            permissions = permissions(overlay = true, mic = true, notifications = true),
            overlayStatus = VoiceOverlayStatus.HIDDEN,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun MainPreview(@PreviewParameter(MainStatePreviewProvider::class) uiState: MainUiState) {
    PreviewTheme {
        MainContent(
            uiState = uiState,
            onRequestPermission = {},
            onStart = {},
            onShow = {},
            onStop = {},
            onMessageShown = {},
        )
    }
}
