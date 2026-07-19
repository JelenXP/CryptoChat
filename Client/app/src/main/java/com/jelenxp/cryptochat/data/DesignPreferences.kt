package com.jelenxp.cryptochat.data

import androidx.compose.ui.graphics.Color

/**
 * Volby vzhledu (obrazovka Nastavení → Vzhled). Ukládají se v
 * [SettingsRepository] jako názvy enumů, takže přežijí restart appky.
 */

/** Motiv: řídit se systémem / vždy světlý / vždy tmavý. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Značková tyrkysová a tři její varianty (stejné 4 barvy jako v návrhu). */
enum class AccentColor(val color: Color) {
    TEAL(Color(0xFF006A60)),
    OCEAN(Color(0xFF00857A)),
    STEEL(Color(0xFF0E6E7C)),
    PINE(Color(0xFF155E52))
}

/** Hustota rozložení (mezery/odsazení). Výchozí kompaktní. */
enum class UiDensity { COMPACT, COMFORTABLE }

/** Zaoblení rohů karet, polí a tlačítek. */
enum class Corners { ROUNDED, SOFT, SHARP }

/** Styl přechodu mezi obrazovkami. */
enum class AnimStyle { SLIDE, FADE, SCALE, NONE }

/** Rychlost přechodu (v milisekundách). Výchozí normální. */
enum class AnimSpeed(val millis: Int) { FAST(160), NORMAL(240), SLOW(340) }
