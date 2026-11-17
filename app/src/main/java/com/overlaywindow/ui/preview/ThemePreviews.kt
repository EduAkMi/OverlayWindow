package com.overlaywindow.ui.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.overlaywindow.ui.theme.OverlayTheme

/** Multipreview: renderiza o mesmo composable nos temas claro e escuro. */
@Preview(name = "Claro", showBackground = true)
@Preview(
    name = "Escuro",
    showBackground = true,
    backgroundColor = 0xFF121212,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
annotation class ThemePreviews

/** Tema da POC com cores fixas — cores dinâmicas (Android 12+) não renderizam no preview. */
@Composable
fun PreviewTheme(content: @Composable () -> Unit) {
    OverlayTheme(dynamicColor = false, content = content)
}
