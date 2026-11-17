package com.overlaywindow.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.overlaywindow.data.permission.AppPermission
import com.overlaywindow.data.permission.PermissionChecker
import com.overlaywindow.overlay.voice.VoiceOverlayController
import com.overlaywindow.overlay.voice.VoiceOverlayStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class PermissionUiState(
    val permission: AppPermission,
    val granted: Boolean,
    val required: Boolean,
)

data class MainUiState(
    val permissions: List<PermissionUiState> = emptyList(),
    val overlayStatus: VoiceOverlayStatus = VoiceOverlayStatus.STOPPED,
    val userMessage: String? = null,
) {
    val canStart: Boolean get() = permissions.none { it.required && !it.granted }
    val isRunning: Boolean get() = overlayStatus != VoiceOverlayStatus.STOPPED
}

class MainViewModel(
    private val permissionChecker: PermissionChecker,
    private val overlayController: VoiceOverlayController,
) : ViewModel() {

    private val local = MutableStateFlow(MainUiState(permissions = readPermissions()))

    val uiState: StateFlow<MainUiState> =
        combine(local, overlayController.status) { state, status -> state.copy(overlayStatus = status) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** Permissões mudam fora do app (tela de configurações) — reavaliar no onResume. */
    fun refresh() {
        local.update { it.copy(permissions = readPermissions()) }
    }

    fun onStartClick() {
        refresh()
        if (!local.value.canStart) {
            local.update { it.copy(userMessage = "Conceda as permissões obrigatórias primeiro.") }
            return
        }
        overlayController.start()
        local.update {
            it.copy(
                userMessage = if (check(AppPermission.NOTIFICATIONS)) {
                    "Overlay iniciado — use a notificação para pausar, esconder ou encerrar."
                } else {
                    "Overlay iniciado sem notificação: em segundo plano, só dá para controlá-lo pela voz ou por este app."
                },
            )
        }
    }

    fun onShowClick() = overlayController.show()

    fun onStopClick() = overlayController.stop()

    fun onMessageShown() {
        local.update { it.copy(userMessage = null) }
    }

    private fun readPermissions() = listOf(
        PermissionUiState(AppPermission.DRAW_OVER_OTHER_APPS, check(AppPermission.DRAW_OVER_OTHER_APPS), required = true),
        PermissionUiState(AppPermission.MICROPHONE, check(AppPermission.MICROPHONE), required = true),
        PermissionUiState(AppPermission.NOTIFICATIONS, check(AppPermission.NOTIFICATIONS), required = false),
    )

    private fun check(permission: AppPermission) = permissionChecker.isGranted(permission)
}
