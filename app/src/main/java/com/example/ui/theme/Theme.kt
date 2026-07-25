package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryDark,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = PrimaryBlue,
    secondary = NeonCyan,
    onSecondary = OnPrimaryDark,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = NeonCyan,
    tertiary = LiveBufferRed,
    onTertiary = Color.White,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = TextMuted
)

@Composable
fun QuadraReplayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
