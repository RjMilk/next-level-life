package com.nextlevellife.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFF5A524)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFF87171)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF232731),
    onSurfaceVariant = Color(0xFFA3A9B6),
    error = Bad,
)

@Composable
fun NextLevelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
