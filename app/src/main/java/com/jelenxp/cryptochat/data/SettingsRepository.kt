package com.jelenxp.cryptochat.data

import android.content.Context

/**
 * Jednoduché úložiště nastavení appky: zámek appky, povolení kopírování klíče
 * a nově i volby vzhledu (motiv, akcent, hustota, rohy, animace přechodů).
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    }

    /**
     * Povoleno kopírování tajného klíče do schránky? Záměrně výchozí `false` -
     * schránka je riziková (můžou ji číst jiné aplikace), bezpečnější je sdílet
     * klíč QR kódem. Netýká se ostatních (necitlivých) věcí - ty jdou kopírovat
     * vždy.
     */
    fun isKeyCopyAllowed(): Boolean = prefs.getBoolean(KEY_KEY_COPY, false)

    fun setKeyCopyAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_KEY_COPY, allowed).apply()
    }

    // --- Vzhled ---
    // Ukládá se název enumu; při čtení bezpečný fallback na výchozí hodnotu,
    // kdyby v prefs zůstala neplatná/stará hodnota.

    fun getThemeMode(): ThemeMode = readEnum(KEY_THEME, ThemeMode.SYSTEM)
    fun setThemeMode(v: ThemeMode) = writeEnum(KEY_THEME, v)

    fun getAccent(): AccentColor = readEnum(KEY_ACCENT, AccentColor.TEAL)
    fun setAccent(v: AccentColor) = writeEnum(KEY_ACCENT, v)

    fun getDensity(): UiDensity = readEnum(KEY_DENSITY, UiDensity.COMPACT)
    fun setDensity(v: UiDensity) = writeEnum(KEY_DENSITY, v)

    fun getCorners(): Corners = readEnum(KEY_CORNERS, Corners.ROUNDED)
    fun setCorners(v: Corners) = writeEnum(KEY_CORNERS, v)

    fun getAnimStyle(): AnimStyle = readEnum(KEY_ANIM_STYLE, AnimStyle.SLIDE)
    fun setAnimStyle(v: AnimStyle) = writeEnum(KEY_ANIM_STYLE, v)

    fun getAnimSpeed(): AnimSpeed = readEnum(KEY_ANIM_SPEED, AnimSpeed.NORMAL)
    fun setAnimSpeed(v: AnimSpeed) = writeEnum(KEY_ANIM_SPEED, v)

    // --- Upozornění na aktualizaci ---
    // Verze naposledy „odložená" tlačítkem Později + kdy, aby se stejná verze
    // nepřipomínala hned zas (viz UpdateGate v MainActivity).

    fun getUpdateDismissedVersion(): String? = prefs.getString(KEY_UPD_VERSION, null)
    fun getUpdateDismissedAt(): Long = prefs.getLong(KEY_UPD_AT, 0L)

    fun setUpdateDismissed(version: String, atMillis: Long) {
        prefs.edit().putString(KEY_UPD_VERSION, version).putLong(KEY_UPD_AT, atMillis).apply()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return runCatching { enumValueOf<T>(name) }.getOrDefault(default)
    }

    private fun writeEnum(key: String, value: Enum<*>) {
        prefs.edit().putString(key, value.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "crypto_chat_settings"
        private const val KEY_APP_LOCK = "app_lock_enabled"
        private const val KEY_KEY_COPY = "key_copy_allowed"
        private const val KEY_THEME = "design_theme"
        private const val KEY_ACCENT = "design_accent"
        private const val KEY_DENSITY = "design_density"
        private const val KEY_CORNERS = "design_corners"
        private const val KEY_ANIM_STYLE = "design_anim_style"
        private const val KEY_ANIM_SPEED = "design_anim_speed"
        private const val KEY_UPD_VERSION = "update_dismissed_version"
        private const val KEY_UPD_AT = "update_dismissed_at"
    }
}
