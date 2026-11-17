package com.overlaywindow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.overlaywindow.data.permission.AndroidPermissionChecker
import com.overlaywindow.overlay.voice.ServiceVoiceOverlayController
import com.overlaywindow.ui.main.MainScreen
import com.overlaywindow.ui.main.MainViewModel
import com.overlaywindow.ui.theme.OverlayTheme

class MainActivity : ComponentActivity() {

    // Projeto pequeno: as dependências são montadas aqui, sem container de DI.
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    permissionChecker = AndroidPermissionChecker(applicationContext),
                    overlayController = ServiceVoiceOverlayController(applicationContext),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OverlayTheme {
                MainScreen(viewModel)
            }
        }
    }
}
