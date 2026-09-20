package com.smartvideo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Sky500,
    onPrimary = Color.White,
    primaryContainer = Slate800,
    onPrimaryContainer = Sky300,
    secondary = Emerald500,
    onSecondary = Color.White,
    background = Slate950,
    onBackground = Color.White,
    surface = Slate900,
    onSurface = Color.White,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate300
)

@Composable
fun SmartVideoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
