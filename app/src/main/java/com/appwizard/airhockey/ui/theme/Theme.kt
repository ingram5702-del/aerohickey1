package com.appwizard.airhockey.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentOrange,
    background = SurfaceDark,
    surface = SurfaceGreen
)

@Composable
fun AirHockeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
