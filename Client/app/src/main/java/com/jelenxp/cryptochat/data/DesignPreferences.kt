package com.jelenxp.cryptochat.data

import androidx.compose.ui.graphics.Color

/**
 * Volby vzhledu (obrazovka Nastavení → Vzhled). Ukládají se v
 * [SettingsRepository] jako názvy enumů, takže přežijí restart appky.
 */

/** Motiv: řídit se systémem / vždy světlý / vždy tmavý. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Značková tyrkysová a tři její varianty (stejné 4 barvy jako v návrhu).
 * @param color hlavní barva accentu (role `primary` v motivu).
 * @param bubble světlé pozadí bubliny VLASTNÍ (odchozí) zprávy v chatu - vlastní
 *   odstín pro každý accent, aby se volba accentu v konverzaci projevila (accenty
 *   jsou jinak skoro k nerozeznání, protože se braly z `primary`). Stejné ve
 *   světlém i tmavém režimu, drženo světlé záměrně.
 * @param onBubble tmavý text a akcent na [bubble] (odstín téže rodiny, čitelný).
 */
enum class AccentColor(val color: Color, val bubble: Color, val onBubble: Color) {
    TEAL(Color(0xFF006A60), Color(0xFF79C6BC), Color(0xFF0D3D37)),
    OCEAN(Color(0xFF00857A), Color(0xFF5FC7C1), Color(0xFF0A3D39)),
    STEEL(Color(0xFF0E6E7C), Color(0xFF6FB9CE), Color(0xFF0B3944)),
    PINE(Color(0xFF155E52), Color(0xFF7FC7A6), Color(0xFF123F2E))
}

/** Hustota rozložení (mezery/odsazení). Výchozí kompaktní. */
enum class UiDensity { COMPACT, COMFORTABLE }

/** Zaoblení rohů karet, polí a tlačítek. */
enum class Corners { ROUNDED, SOFT, SHARP }

/** Styl přechodu mezi obrazovkami. */
enum class AnimStyle { SLIDE, FADE, SCALE, NONE }

/** Rychlost přechodu (v milisekundách). Výchozí normální. */
enum class AnimSpeed(val millis: Int) { FAST(160), NORMAL(240), SLOW(340) }
