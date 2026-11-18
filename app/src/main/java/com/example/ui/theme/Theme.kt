package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VpnDarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = TextPrimary,
    primaryContainer = ElevatedNavy,
    onPrimaryContainer = ElectricBlueGlow,
    secondary = AmethystPurple,
    onSecondary = TextPrimary,
    secondaryContainer = DeepPurple,
    onSecondaryContainer = TextPrimary,
    tertiary = CyanAccent,
    onTertiary = ObsidianBlack,
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = MidnightNavy,
    onSurface = TextPrimary,
    surfaceVariant = DeepNavySurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBlack,
    surfaceContainerLow = MidnightNavy,
    surfaceContainer = DeepNavySurface,
    surfaceContainerHigh = ElevatedNavy,
    outline = CardBorderNavy,
    outlineVariant = ColorTokens.SubtleDivider,
    error = CoralError,
    onError = TextPrimary,
    errorContainer = CoralContainer,
    onErrorContainer = TextPrimary
)

object ColorTokens {
    val SubtleDivider = androidx.compose.ui.graphics.Color(0xFF1B2744)
}

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun FreePublicVpnTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VpnDarkColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
