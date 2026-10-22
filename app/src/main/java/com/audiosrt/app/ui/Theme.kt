package com.audiosrt.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Kuning subtitle klasik di atas layar gelap.
private val Dark = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF2B2100),
    primaryContainer = Color(0xFF4A3B00),
    onPrimaryContainer = Color(0xFFFFE7A0),
    secondary = Color(0xFFB8C4D6),
    background = Color(0xFF121418),
    surface = Color(0xFF121418),
    surfaceVariant = Color(0xFF262A31),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF22262C),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6E5700),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE08A),
    onPrimaryContainer = Color(0xFF221B00),
    secondary = Color(0xFF4F5B6B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
