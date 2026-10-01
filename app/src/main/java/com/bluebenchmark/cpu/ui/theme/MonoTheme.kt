package com.bluebenchmark.cpu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppBackground = Color(0xFFF4F0E5)
val AppSurface = Color(0xFFEAE5D9)
val AppSurfaceRaised = Color(0xFFF8F5EC)
val AppBorder = Color(0xFF39362F)
val AppAccent = Color(0xFF6300D8)
val AppAccentDeep = Color(0xFF4F00B2)
val AppOrange = Color(0xFFF3540A)
val AppFocus = Color(0xFF9BBE18)
val AppText = Color(0xFF1D1B18)
val AppMuted = Color(0xFF625E55)
val AppWarning = Color(0xFFE85A19)
val AppError = Color(0xFFB62F27)

private val LightScheme = lightColorScheme(
    primary = AppAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8D9FF),
    onPrimaryContainer = AppText,
    secondary = AppAccentDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE0D4),
    onSecondaryContainer = AppText,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurfaceRaised,
    onSurface = AppText,
    surfaceVariant = AppSurface,
    onSurfaceVariant = AppMuted,
    outline = AppBorder,
    error = AppError,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF31104F),
    onPrimaryContainer = Color(0xFFF3E7FF),
    secondary = AppAccentDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4F2112),
    onSecondaryContainer = Color(0xFFFFE7DB),
    background = Color(0xFF171511),
    onBackground = Color(0xFFF3EFE4),
    surface = Color(0xFF211E19),
    onSurface = Color(0xFFF3EFE4),
    surfaceVariant = Color(0xFF2B2720),
    onSurfaceVariant = Color(0xFFC2BBAE),
    outline = Color(0xFF5E574D),
    error = Color(0xFFFF7676),
    onError = Color(0xFF280000)
)

@Composable
fun MonoTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

// Kept as color aliases for the legacy screens while they share the new palette.
val MonoBlack = AppBackground
val MonoCard = AppSurface
val MonoBorder = AppBorder
val MonoWhite = AppText
val MonoGray = AppMuted
val MonoDim = AppBorder
