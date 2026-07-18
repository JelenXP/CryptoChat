package com.jelenxp.cryptochat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.data.Corners
import com.jelenxp.cryptochat.data.ThemeMode

/** Zesvětlení/ztmavení barvy o poměr [f] (0..1) - míchání s bílou/černou. */
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

/**
 * Světlé schéma odvozené od zvoleného akcentu. Neutrální plochy zůstávají
 * (Light* z Color.kt), mění se jen skupina "primary".
 */
private fun lightSchemeFor(a: Color) = lightColorScheme(
    primary = a,
    onPrimary = Color.White,
    primaryContainer = a.lighten(0.80f),
    onPrimaryContainer = a.darken(0.62f),
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer
)

/**
 * Tmavé schéma (návrh 1a "Neutral graphite"). Akcent se v tmavém režimu
 * automaticky rozjasní a plná tlačítka dostanou tmavý text, aby byl dostatečný
 * kontrast; plochy jsou hluboká neutrální zeleno-černá.
 */
private fun darkSchemeFor(a: Color): androidx.compose.material3.ColorScheme {
    val bright = a.lighten(0.50f)
    return darkColorScheme(
        primary = bright,
        onPrimary = a.darken(0.60f),
        primaryContainer = a.darken(0.30f),
        onPrimaryContainer = bright,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = Color(0xFF232B27),
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        background = Color(0xFF0F1413),
        onBackground = Color(0xFFE6E9E6),
        surface = Color(0xFF1B2320),
        onSurface = Color(0xFFE6E9E6),
        surfaceVariant = Color(0xFF232B27),
        onSurfaceVariant = Color(0xFF9EA8A2),
        outline = Color(0xFF6E7A76),
        outlineVariant = Color(0xFF333C38),
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer
    )
}

/** Tvary (zaoblení) podle zvolené volby v Nastavení → Vzhled. */
private fun shapesFor(corners: Corners): Shapes = when (corners) {
    Corners.SOFT -> Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp)
    )
    Corners.SHARP -> Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(10.dp),
        extraLarge = RoundedCornerShape(12.dp)
    )
    else -> AppShapes // ROUNDED = původní jednotné zaoblení
}

/**
 * Motiv aplikace. Barvy, tvary i světlý/tmavý režim se řídí volbami vzhledu
 * z [DesignController] (Nastavení → Vzhled). Bez Material You dynamických barev,
 * aby appka vypadala všude stejně.
 */
@Composable
fun CryptoChatTheme(
    controller: DesignController,
    content: @Composable () -> Unit
) {
    val dark = when (controller.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = controller.accent.color
    MaterialTheme(
        colorScheme = if (dark) darkSchemeFor(accent) else lightSchemeFor(accent),
        typography = AppTypography,
        shapes = shapesFor(controller.corners),
        content = content
    )
}
