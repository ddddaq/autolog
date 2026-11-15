package com.autolog.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF16324F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7F3),
    onPrimaryContainer = Color(0xFF0E2338),
    secondaryContainer = Color(0xFFE6EBF0),
    onSecondaryContainer = Color(0xFF16324F),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111418),
    surface = Color.White,
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE9EDF1),
    onSurfaceVariant = Color(0xFF4A5560),
    outline = Color(0xFFC7CED6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC2EA),
    onPrimary = Color(0xFF0B2138),
    primaryContainer = Color(0xFF1E3A57),
    onPrimaryContainer = Color(0xFFDCE7F3),
    secondaryContainer = Color(0xFF2A323B),
    onSecondaryContainer = Color(0xFFDCE7F3),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE9EDF1),
    surface = Color(0xFF171B20),
    onSurface = Color(0xFFE9EDF1),
    surfaceVariant = Color(0xFF242A31),
    onSurfaceVariant = Color(0xFFA9B4BF),
    outline = Color(0xFF3A434D),
)

@Composable
fun AutoLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
