package com.pixel9.signalsurvey.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark only. The app is a camera viewfinder with overlays on top; a light scheme would wash
 * out every annotation and is never the right choice for AR.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFF5CC8FF),
    onPrimary = Color(0xFF001E2C),
    secondary = Color(0xFF7FD4A8),
    onSecondary = Color(0xFF00301C),
    tertiary = Color(0xFF9B8CFF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE4EAF0),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE4EAF0),
    surfaceVariant = Color(0xFF1A222C),
    onSurfaceVariant = Color(0xFFB9C4D0),
    error = Color(0xFFFF8B8B),
)

@Composable
fun SignalSurveyTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
