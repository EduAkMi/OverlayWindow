package com.overlaywindow.overlay.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.overlaywindow.R
import com.overlaywindow.overlay.voice.data.AndroidVoiceEngine
import com.overlaywindow.overlay.voice.domain.VoiceCommandInterpreter
import com.overlaywindow.overlay.voice.domain.VoiceEngine
import com.overlaywindow.overlay.voice.ui.OverlayMode
import com.overlaywindow.overlay.voice.ui.VoiceOverlayRoot
import com.overlaywindow.overlay.voice.ui.VoiceOverlayViewModel
import com.overlaywindow.overlay.window.ComposeOverlayWindow
import com.overlaywindow.ui.theme.OverlayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service do tipo `microphone` que hospeda o overlay de voz.
 *
 * Por que o tipo microphone? Desde o Android 11 o microfone é uma permissão "while-in-use":
 * em background só pode ser usado por um foreground service desse tipo, e esse service precisa
 * ser **iniciado enquanto o app está em primeiro plano**. Uma vez iniciado, o acesso continua
 * com o app em background — e é por isso que "fechar" o overlay só *esconde* a janela: se o
 * service parasse, não haveria como religar o microfone sem o usuário abrir o app de novo.
 *
 * O service é a "composition root" do overlay: cria o [VoiceEngine] (que precisa morrer junto
 * com o service) e a factory do ViewModel. Também é ele quem aplica o [OverlayMode] à janela,
 * porque no modo escondido não existe composição para fazer isso.
 *
 * Autocontido: cria o próprio canal de notificação e expõe [status] para quem o inicia.
 */
class VoiceOverlayService : Service() {

    private var window: ComposeOverlayWindow? = null
    private var engine: VoiceEngine? = null
    private var viewModel: VoiceOverlayViewModel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // Ações da notificação / tela principal: o service já está em execução.
            ACTION_SHOW -> {
                viewModel?.onShow() ?: stopSelfIfIdle()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> {
                viewModel?.onHide() ?: stopSelfIfIdle()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                viewModel?.onPauseListening() ?: stopSelfIfIdle()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                viewModel?.onResumeListening() ?: stopSelfIfIdle()
                return START_NOT_STICKY
            }
        }

        // Início: startForegroundService() exige startForeground() logo em seguida.
        if (window != null) return START_NOT_STICKY
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val started = startAsForeground(micGranted)
        if (!started || !micGranted || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        createWindow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        window?.destroy() // limpa o ViewModelStore → VoiceOverlayViewModel.onCleared()
        window = null
        viewModel = null
        engine?.release()
        engine = null
        _status.value = VoiceOverlayStatus.STOPPED
        super.onDestroy()
    }

    private fun stopSelfIfIdle() {
        if (window == null) stopSelf()
    }

    private fun createWindow() {
        val voiceEngine = AndroidVoiceEngine(this).also { engine = it }
        val factory = viewModelFactory {
            initializer { VoiceOverlayViewModel(voiceEngine, VoiceCommandInterpreter()) }
        }

        lateinit var overlay: ComposeOverlayWindow
        // Mesmo ViewModelStore da janela → é a mesma instância usada pelo Compose.
        val vm by lazy { ViewModelProvider(overlay, factory)[VoiceOverlayViewModel::class.java] }
        overlay = ComposeOverlayWindow(
            context = this,
            windowType = overlayWindowType(),
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            width = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true,
            // "Voltar" que o NavHost não consumiu (já na tela inicial) → recolhe o overlay.
            onBack = { vm.onCollapse() },
        ) {
            OverlayTheme {
                VoiceOverlayRoot(viewModel = vm, onStop = { stopSelf() })
            }
        }
        window = overlay
        viewModel = vm

        // A janela segue o modo do ViewModel — inclusive para reaparecer quando a frase de
        // ativação é reconhecida com ela escondida.
        scope.launch {
            vm.uiState.map { it.mode to it.micPaused }.distinctUntilChanged().collect { (mode, paused) ->
                applyMode(mode)
                updateNotification(mode, paused)
            }
        }
    }

    private fun applyMode(mode: OverlayMode) {
        val overlay = window ?: return
        when (mode) {
            // Expandido: largura total + foco (teclado e "voltar").
            OverlayMode.EXPANDED -> {
                overlay.setWidth(WindowManager.LayoutParams.MATCH_PARENT)
                overlay.setFocusable(true)
                overlay.show()
            }
            // Recolhido: só o tamanho da pílula, sem roubar foco do app de baixo.
            OverlayMode.COLLAPSED -> {
                overlay.setWidth(WindowManager.LayoutParams.WRAP_CONTENT)
                overlay.setFocusable(false)
                overlay.show()
            }
            // Escondido: a janela sai da tela; o service e o microfone continuam.
            OverlayMode.HIDDEN -> overlay.hide()
        }
        _status.value =
            if (mode == OverlayMode.HIDDEN) VoiceOverlayStatus.HIDDEN else VoiceOverlayStatus.VISIBLE
    }

    private fun startAsForeground(micGranted: Boolean): Boolean {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Assistente de voz")
                .setDescription("Mantém o overlay de voz ativo")
                .build(),
        )

        var type = 0
        if (micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(OverlayMode.EXPANDED, paused = false), type,
            )
            true
        } catch (e: SecurityException) {
            // Ex.: tentativa de iniciar o tipo microphone com o app em background.
            Log.w(TAG, "Não foi possível iniciar o foreground service de microfone", e)
            false
        }
    }

    /** Sem POST_NOTIFICATIONS (Android 13+) a notificação não aparece; o service roda igual. */
    private fun updateNotification(mode: OverlayMode, paused: Boolean) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(mode, paused))
    }

    /**
     * Notificação = painel de controle do assistente em segundo plano:
     * mostra se o microfone está ativo e oferece Mostrar/Esconder, Pausar/Retomar e Encerrar.
     */
    private fun buildNotification(mode: OverlayMode, paused: Boolean): Notification {
        val hidden = mode == OverlayMode.HIDDEN
        val showIntent = servicePendingIntent(REQUEST_SHOW, ACTION_SHOW)
        val title = when {
            paused -> "Assistente pausado"
            hidden -> "Assistente ouvindo em segundo plano"
            else -> "Assistente de voz ativo"
        }
        val text = when {
            paused -> "Microfone desligado. Toque em \"Retomar\" para voltar a ouvir."
            hidden -> "Microfone ativo. Diga \"${VoiceCommandInterpreter.WAKE_PHRASE}\" para reabrir."
            else -> "Microfone ativo. O overlay está na tela."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(showIntent)
            .addAction(
                0,
                if (hidden) "Mostrar" else "Esconder",
                if (hidden) showIntent else servicePendingIntent(REQUEST_HIDE, ACTION_HIDE),
            )
            .addAction(
                0,
                if (paused) "Retomar" else "Pausar",
                servicePendingIntent(
                    if (paused) REQUEST_RESUME else REQUEST_PAUSE,
                    if (paused) ACTION_RESUME else ACTION_PAUSE,
                ),
            )
            .addAction(0, "Encerrar", servicePendingIntent(REQUEST_STOP, ACTION_STOP))
            .build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, VoiceOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    companion object {
        private const val TAG = "VoiceOverlayService"
        private const val ACTION_STOP = "com.overlaywindow.action.STOP_VOICE_OVERLAY"
        private const val ACTION_SHOW = "com.overlaywindow.action.SHOW_VOICE_OVERLAY"
        private const val ACTION_HIDE = "com.overlaywindow.action.HIDE_VOICE_OVERLAY"
        private const val ACTION_PAUSE = "com.overlaywindow.action.PAUSE_LISTENING"
        private const val ACTION_RESUME = "com.overlaywindow.action.RESUME_LISTENING"
        private const val CHANNEL_ID = "voice_overlay_service"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_STOP = 0
        private const val REQUEST_SHOW = 1
        private const val REQUEST_HIDE = 2
        private const val REQUEST_PAUSE = 3
        private const val REQUEST_RESUME = 4

        private val _status = MutableStateFlow(VoiceOverlayStatus.STOPPED)

        /** Situação atual do overlay. Observado pela tela principal. */
        val status: StateFlow<VoiceOverlayStatus> = _status.asStateFlow()

        /** Inicia o service. Precisa ser chamado com o app em primeiro plano. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceOverlayService::class.java))
        }

        /** Reexibe a janela de um service já em execução. */
        fun show(context: Context) {
            context.startService(Intent(context, VoiceOverlayService::class.java).setAction(ACTION_SHOW))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceOverlayService::class.java))
        }
    }
}
