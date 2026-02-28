package com.vault.srd.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MonochromeColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White
)

@Composable
fun TheVaultTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        content = content
    )
}
