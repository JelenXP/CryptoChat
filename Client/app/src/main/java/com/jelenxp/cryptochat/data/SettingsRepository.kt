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

    /**
     * Kontrolovat při startu na GitHubu novější verzi? Výchozí `true`. Vypnutím
     * appka při startu nedělá vůbec žádný síťový dotaz.
     */
    fun isUpdateCheckEnabled(): Boolean = prefs.getBoolean(KEY_UPD_CHECK, true)
    fun setUpdateCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPD_CHECK, enabled).apply()
    }

    // Verze naposledy „odložená" tlačítkem Později + kdy, aby se stejná verze
    // nepřipomínala hned zas (viz StartupGate v MainActivity).

    fun getUpdateDismissedVersion(): String? = prefs.getString(KEY_UPD_VERSION, null)
    fun getUpdateDismissedAt(): Long = prefs.getLong(KEY_UPD_AT, 0L)

    fun setUpdateDismissed(version: String, atMillis: Long) {
        prefs.edit().putString(KEY_UPD_VERSION, version).putLong(KEY_UPD_AT, atMillis).apply()
    }

    // --- Pozastavení připomínání aktualizací (na 30 dní) ---
    // Kontrola nové verze běží dál (pokud není vypnutá výše); jen se během
    // pozastavení nepřipomínají běžné aktualizace. Důležitá (`important`) verze
    // se i tak připomene jednou - to hlídá `SnoozeImportantShown` (uloží se
    // verze, která už během pozastavení byla ukázána).

    /** Do kdy (epoch millis) je připomínání pozastavené; 0 = není. */
    fun getUpdateSnoozeUntil(): Long = prefs.getLong(KEY_UPD_SNOOZE_UNTIL, 0L)

    /** Zapne pozastavení do daného času a vynuluje značku ukázaného důležitého. */
    fun setUpdateSnooze(untilMillis: Long) {
        prefs.edit()
            .putLong(KEY_UPD_SNOOZE_UNTIL, untilMillis)
            .remove(KEY_UPD_SNOOZE_IMPORTANT)
            .apply()
    }

    /** Vypne pozastavení (a zapomene značku ukázaného důležitého). */
    fun clearUpdateSnooze() {
        prefs.edit()
            .remove(KEY_UPD_SNOOZE_UNTIL)
            .remove(KEY_UPD_SNOOZE_IMPORTANT)
            .apply()
    }

    /** Verze důležité aktualizace, která už během pozastavení byla připomenuta. */
    fun getUpdateSnoozeImportantShown(): String? = prefs.getString(KEY_UPD_SNOOZE_IMPORTANT, null)
    fun setUpdateSnoozeImportantShown(version: String) {
        prefs.edit().putString(KEY_UPD_SNOOZE_IMPORTANT, version).apply()
    }

    // Naposledy „viděná" verze appky (versionCode) - pro zobrazení novinek po
    // aktualizaci. 0 = ještě nezaznamenáno (čerstvá instalace).
    fun getLastSeenVersionCode(): Int = prefs.getInt(KEY_LAST_SEEN_VC, 0)
    fun setLastSeenVersionCode(code: Int) {
        prefs.edit().putInt(KEY_LAST_SEEN_VC, code).apply()
    }

    // --- Limit velikosti šifrovaných souborů ---
    // Výchozí zapnutý (strop 256 MB). Vypnutím jde šifrovat i větší soubory za
    // cenu delšího zpracování a většího nároku na místo (viz upozornění v UI).
    fun isFileSizeLimitEnabled(): Boolean = prefs.getBoolean(KEY_FILE_LIMIT, true)
    fun setFileSizeLimitEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILE_LIMIT, enabled).apply()
    }

    // --- Relay (chat přes server) ---
    // Dva režimy: VÝCHOZÍ (zabudovaná .onion adresa oficiálního serveru) nebo
    // VLASTNÍ (uživatel si zadá svou adresu, např. http://192.168.1.10:8787 nebo
    // jinou .onion). Čerstvá instalace startuje na VÝCHOZÍM serveru, takže chat
    // funguje hned. Necitlivé hodnoty, plaintext.

    /** Je zapnutý režim VLASTNÍ adresy? Výchozí `false` = použít výchozí server. */
    fun isUsingCustomRelay(): Boolean = prefs.getBoolean(KEY_RELAY_CUSTOM, false)
    fun setUsingCustomRelay(custom: Boolean) {
        prefs.edit().putBoolean(KEY_RELAY_CUSTOM, custom).apply()
    }

    /** Uživatelská adresa serveru (uplatní se jen v režimu VLASTNÍ). */
    fun getRelayCustomUrl(): String = prefs.getString(KEY_RELAY_URL, "")?.trim().orEmpty()
    fun setRelayCustomUrl(url: String) {
        prefs.edit().putString(KEY_RELAY_URL, url.trim()).apply()
    }

    /**
     * Efektivní adresa serveru, kterou používá zbytek appky (RelayClient,
     * RelaySync, Tor…). V režimu VÝCHOZÍ vrací zabudovanou [DEFAULT_RELAY_URL],
     * jinak uživatelskou adresu. Prázdná (VLASTNÍ + nevyplněno) = chat přes
     * server je vypnutý a appka jede jako offline.
     */
    fun getRelayUrl(): String =
        if (isUsingCustomRelay()) getRelayCustomUrl() else DEFAULT_RELAY_URL

    // --- Onboarding (první spuštění) ---
    // Průvodce povoleními pro běh na pozadí (notifikace, baterie, autostart).
    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
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
        private const val KEY_UPD_CHECK = "update_check_enabled"
        private const val KEY_UPD_VERSION = "update_dismissed_version"
        private const val KEY_UPD_AT = "update_dismissed_at"
        private const val KEY_UPD_SNOOZE_UNTIL = "update_snooze_until"
        private const val KEY_UPD_SNOOZE_IMPORTANT = "update_snooze_important_shown"
        private const val KEY_LAST_SEEN_VC = "last_seen_version_code"
        private const val KEY_FILE_LIMIT = "file_size_limit_enabled"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_CUSTOM = "relay_use_custom"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"

        /**
         * Výchozí (zabudovaná) adresa oficiálního zero-knowledge relaye přes Tor.
         * Na tuhle appka míří, dokud si uživatel nepřepne na vlastní server.
         */
        const val DEFAULT_RELAY_URL =
            "http://m7rwivoh3yzvzbvi2m5ob3qau5wtik5f46qsphjycoyof7gcwfbjnaad.onion"
    }
}
