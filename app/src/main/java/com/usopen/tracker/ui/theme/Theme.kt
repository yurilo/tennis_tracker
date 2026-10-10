package com.usopen.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = UsOpenNavy,
    secondary = UsOpenCourtBlue,
    tertiary = UsOpenGold,
    background = BackgroundLight,
    surface = SurfaceLight,
    onPrimary = SurfaceLight,
    onSecondary = SurfaceLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = UsOpenGold,
    secondary = UsOpenCourtBlue,
    tertiary = UsOpenTennisGreen,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = UsOpenNavyDark,
    onSecondary = SurfaceLight,
    onBackground = SurfaceLight,
    onSurface = SurfaceLight
)

@Composable
fun UsOpenTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
