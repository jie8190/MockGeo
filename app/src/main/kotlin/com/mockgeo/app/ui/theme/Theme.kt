package com.mockgeo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2563EB),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEAF1FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1745A5),
    secondary = androidx.compose.ui.graphics.Color(0xFF506078),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFECF0F6),
    tertiary = androidx.compose.ui.graphics.Color(0xFF0B8277),
    surface = androidx.compose.ui.graphics.Color(0xFFFCFDFF),
    background = androidx.compose.ui.graphics.Color(0xFFF3F6FB),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF0F4FA),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF6F8FC),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE8EEF6),
    onSurface = androidx.compose.ui.graphics.Color(0xFF182237),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF68758A),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE0E6EF),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF9ABDFF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF082C70),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF193B75),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD9E6FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFB9C7DC),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF29364A),
    tertiary = androidx.compose.ui.graphics.Color(0xFF70D5C8),
    surface = androidx.compose.ui.graphics.Color(0xFF111927),
    background = androidx.compose.ui.graphics.Color(0xFF0D1420),
)

@Composable
fun MockGeoTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        // MaterialTheme does not reset LocalContentColor. Custom/alpha surfaces fall
        // back to this value, so a nested user-selected theme must override it too.
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            content()
        }
    }
}
