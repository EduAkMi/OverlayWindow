package com.overlaywindow.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class AppPermission {
    /** SYSTEM_ALERT_WINDOW — concedida na tela de configurações. */
    DRAW_OVER_OTHER_APPS,

    /** RECORD_AUDIO — runtime permission. */
    MICROPHONE,

    /** POST_NOTIFICATIONS (Android 13+) — opcional: só exibe a notificação do serviço. */
    NOTIFICATIONS,
}

interface PermissionChecker {
    fun isGranted(permission: AppPermission): Boolean
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun isGranted(permission: AppPermission): Boolean = when (permission) {
        AppPermission.DRAW_OVER_OTHER_APPS -> Settings.canDrawOverlays(context)
        AppPermission.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)
        AppPermission.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
