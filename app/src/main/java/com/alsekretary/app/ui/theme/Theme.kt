package com.alsekretary.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Deep = Color(0xFF061719)
private val Surface = Color(0xFF0A2527)
private val Raised = Color(0xFF103638)
private val Accent = Color(0xFF42D8B4)
private val Text = Color(0xFFF2F5F4)
private val Muted = Color(0xFF9CB7B4)
private val Danger = Color(0xFFFF8A80)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201A),
    background = Deep,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    error = Danger
)

@Composable
fun AlSekretaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
