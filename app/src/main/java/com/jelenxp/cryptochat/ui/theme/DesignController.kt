package com.jelenxp.cryptochat.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.data.AccentColor
import com.jelenxp.cryptochat.data.AnimSpeed
import com.jelenxp.cryptochat.data.AnimStyle
import com.jelenxp.cryptochat.data.Corners
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.data.ThemeMode
import com.jelenxp.cryptochat.data.UiDensity

/** Odsazení/mezery pro danou hustotu (viz [UiDensity]). */
data class UiSpacing(val screenPad: Dp, val cardInner: Dp, val itemGap: Dp)

val CompactSpacing = UiSpacing(screenPad = 12.dp, cardInner = 14.dp, itemGap = 8.dp)
val ComfortableSpacing = UiSpacing(screenPad = 16.dp, cardInner = 18.dp, itemGap = 12.dp)

fun UiDensity.spacing(): UiSpacing =
    if (this == UiDensity.COMFORTABLE) ComfortableSpacing else CompactSpacing

/**
 * Stavový držák voleb vzhledu. Čte výchozí hodnoty z [SettingsRepository],
 * změny hned uloží. Čtení `themeMode`/`accent`/... uvnitř @Composable spustí
 * rekompozici, takže se změna vzhledu projeví okamžitě v celé appce.
 */
class DesignController(private val repo: SettingsRepository) {
    private var _theme by mutableStateOf(repo.getThemeMode())
    private var _accent by mutableStateOf(repo.getAccent())
    private var _density by mutableStateOf(repo.getDensity())
    private var _corners by mutableStateOf(repo.getCorners())
    private var _animStyle by mutableStateOf(repo.getAnimStyle())
    private var _animSpeed by mutableStateOf(repo.getAnimSpeed())

    val themeMode: ThemeMode get() = _theme
    val accent: AccentColor get() = _accent
    val density: UiDensity get() = _density
    val corners: Corners get() = _corners
    val animStyle: AnimStyle get() = _animStyle
    val animSpeed: AnimSpeed get() = _animSpeed

    fun setThemeMode(v: ThemeMode) { _theme = v; repo.setThemeMode(v) }
    fun setAccent(v: AccentColor) { _accent = v; repo.setAccent(v) }
    fun setDensity(v: UiDensity) { _density = v; repo.setDensity(v) }
    fun setCorners(v: Corners) { _corners = v; repo.setCorners(v) }
    fun setAnimStyle(v: AnimStyle) { _animStyle = v; repo.setAnimStyle(v) }
    fun setAnimSpeed(v: AnimSpeed) { _animSpeed = v; repo.setAnimSpeed(v) }
}

/** Dostupné napříč appkou (poskytuje se v MainActivity). */
val LocalDesign = staticCompositionLocalOf<DesignController> {
    error("DesignController not provided")
}

/** Aktuální odsazení podle zvolené hustoty (poskytuje se v MainActivity). */
val LocalUiSpacing = staticCompositionLocalOf { CompactSpacing }
