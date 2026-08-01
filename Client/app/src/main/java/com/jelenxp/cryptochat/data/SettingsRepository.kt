package com.jelenxp.cryptochat.data

import android.content.Context

/**
 * Jednoduché úložiště nastavení appky: zámek appky a volby vzhledu (motiv,
 * akcent, hustota, rohy, animace přechodů).
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    }

    /**
     * Za jak dlouho po odchodu na pozadí se zámek zamkne (grace period). Výchozí
     * [LockDelay.SEC_10] = původní pevné chování (10 s). Čte se čerstvě při každém
     * návratu do appky, takže se změna projeví hned.
     */
    fun getLockDelay(): LockDelay = readEnum(KEY_LOCK_DELAY, LockDelay.SEC_10)
    fun setLockDelay(v: LockDelay) = writeEnum(KEY_LOCK_DELAY, v)

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

    // --- Úspora dat na pozadí ---

    /**
     * Kdy má chat na pozadí přepnout do úsporného režimu (delší long-poll, delší
     * backoff, žádná kontrola aktualizací na síti). Výchozí [SaverMode.AUTO] =
     * šetřit jen na měřené síti (mobilní data). Viz [decideNetworkProfile].
     */
    fun getSaverMode(): SaverMode = readEnum(KEY_SAVER_MODE, SaverMode.AUTO)
    fun setSaverMode(v: SaverMode) = writeEnum(KEY_SAVER_MODE, v)

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

    // Kdy naposled kontrolovala nové vydání služba na pozadí. Drží se zvlášť od
    // startovní kontroly, aby se z pozadí chodilo na síť nejvýš jednou za den -
    // víc probuzení a onion requestů by stálo baterii.
    fun getUpdateLastCheckAt(): Long = prefs.getLong(KEY_UPD_LAST_CHECK, 0L)
    fun setUpdateLastCheckAt(millis: Long) {
        prefs.edit().putLong(KEY_UPD_LAST_CHECK, millis).apply()
    }

    /** Verze, o které už notifikace přišla - ať nechodí znovu při každé kontrole. */
    fun getUpdateNotifiedVersion(): String? = prefs.getString(KEY_UPD_NOTIFIED, null)
    fun setUpdateNotifiedVersion(version: String) {
        prefs.edit().putString(KEY_UPD_NOTIFIED, version).apply()
    }

    /** Kolikrát po sobě kontrola selhala - vstup pro backoff. */
    fun getUpdateCheckFailures(): Int = prefs.getInt(KEY_UPD_FAILURES, 0)
    fun setUpdateCheckFailures(count: Int) {
        prefs.edit().putInt(KEY_UPD_FAILURES, count.coerceAtLeast(0)).apply()
    }

    // Naposledy „viděná" verze appky (versionCode) - pro zobrazení novinek po
    // aktualizaci. 0 = ještě nezaznamenáno (čerstvá instalace).
    fun getLastSeenVersionCode(): Int = prefs.getInt(KEY_LAST_SEEN_VC, 0)
    fun setLastSeenVersionCode(code: Int) {
        prefs.edit().putInt(KEY_LAST_SEEN_VC, code).apply()
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

    // --- Způsob připojení k VÝCHOZÍMU serveru (Tor vs. Cloudflare) ---
    // Uplatní se JEN v režimu VÝCHOZÍ. Tor = zabudovaná .onion adresa (nejvyšší
    // soukromí), Cloudflare = přímá https adresa (rychlé, ale Cloudflare i server
    // vidí IP). U VLASTNÍ adresy o transportu rozhoduje sama adresa (.onion → Tor,
    // http/https → napřímo), tahle volba se tam neuplatní. Výchozí [ConnectionMode.TOR].

    fun getConnectionMode(): ConnectionMode = readEnum(KEY_CONN_MODE, ConnectionMode.TOR)
    fun setConnectionMode(v: ConnectionMode) = writeEnum(KEY_CONN_MODE, v)

    /**
     * Potvrdil už uživatel volbu způsobu připojení (úvodní obrazovka)? Dokud ne,
     * ukazuje se po startu (viz StartupGate v MainActivity). Výchozí `false`.
     */
    fun isConnectionChoiceMade(): Boolean = prefs.getBoolean(KEY_CONN_CHOICE_MADE, false)
    fun setConnectionChoiceMade(made: Boolean) {
        prefs.edit().putBoolean(KEY_CONN_CHOICE_MADE, made).apply()
    }

    /**
     * Efektivní adresa serveru, kterou používá zbytek appky (RelayClient,
     * RelaySync, Tor…). V režimu VÝCHOZÍ vrací zabudovanou adresu podle
     * [getConnectionMode] ([DEFAULT_RELAY_URL] přes Tor, nebo
     * [DEFAULT_RELAY_URL_CLOUDFLARE] napřímo), jinak uživatelskou adresu. Prázdná
     * (VLASTNÍ + nevyplněno) = chat přes server je vypnutý a appka jede jako offline.
     */
    fun getRelayUrl(): String =
        if (isUsingCustomRelay()) getRelayCustomUrl()
        else when (getConnectionMode()) {
            ConnectionMode.TOR -> DEFAULT_RELAY_URL
            ConnectionMode.CLOUDFLARE -> DEFAULT_RELAY_URL_CLOUDFLARE
        }

    /**
     * Záložní relaye (jeden na řádek). Když primární neodpoví, odeslání zkusí je
     * v pořadí (failover). ID schránek na adrese relaye NEzávisí, takže tatáž
     * schránka existuje na kterémkoli serveru - failover nepotřebuje koordinaci.
     */
    fun getRelayFallbackUrls(): List<String> =
        prefs.getString(KEY_RELAY_FALLBACKS, "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun setRelayFallbackUrls(text: String) {
        prefs.edit().putString(KEY_RELAY_FALLBACKS, text.trim()).apply()
    }

    /** Text pro editaci pole záložních relayí (jeden na řádek). */
    fun getRelayFallbackText(): String = prefs.getString(KEY_RELAY_FALLBACKS, "").orEmpty()

    /**
     * Efektivní seznam relayí: primární první, pak záložní (bez duplicit a prázdných).
     * Prázdný jen když je chat vypnutý (prázdná efektivní adresa).
     */
    fun getRelayUrls(): List<String> {
        val out = ArrayList<String>()
        getRelayUrl().takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (u in getRelayFallbackUrls()) if (u !in out) out.add(u)
        return out
    }

    // --- Onboarding (první spuštění) ---
    // Průvodce povoleními pro běh na pozadí (notifikace, baterie, autostart).
    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    // --- Jazyk aplikace ---
    // BCP-47 tag zvoleného jazyka ("cs"/"en") nebo "" = jazyk systému. Jazyk se
    // přepíná ZA BĚHU (viz AppLocale) bez recreate, proto ho držíme tady, ne v
    // AppCompat úložišti. Notifikace (mimo Compose) si podle něj lokalizují Context.
    fun getLanguageTag(): String = prefs.getString(KEY_LANGUAGE, "").orEmpty()
    fun setLanguageTag(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /** Proběhla už jednorázová migrace jazyka ze starého AppCompat úložiště? */
    fun isLanguageMigrated(): Boolean = prefs.getBoolean(KEY_LANGUAGE_MIGRATED, false)
    fun setLanguageMigrated(done: Boolean) {
        prefs.edit().putBoolean(KEY_LANGUAGE_MIGRATED, done).apply()
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
        private const val KEY_LOCK_DELAY = "app_lock_delay"
        private const val KEY_THEME = "design_theme"
        private const val KEY_ACCENT = "design_accent"
        private const val KEY_DENSITY = "design_density"
        private const val KEY_CORNERS = "design_corners"
        private const val KEY_ANIM_STYLE = "design_anim_style"
        private const val KEY_ANIM_SPEED = "design_anim_speed"
        private const val KEY_SAVER_MODE = "data_saver_mode"
        private const val KEY_UPD_CHECK = "update_check_enabled"
        private const val KEY_UPD_VERSION = "update_dismissed_version"
        private const val KEY_UPD_AT = "update_dismissed_at"
        private const val KEY_UPD_LAST_CHECK = "update_last_check_at"
        private const val KEY_UPD_NOTIFIED = "update_notified_version"
        private const val KEY_UPD_FAILURES = "update_check_failures"
        private const val KEY_LAST_SEEN_VC = "last_seen_version_code"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_CUSTOM = "relay_use_custom"
        private const val KEY_RELAY_FALLBACKS = "relay_fallbacks"
        private const val KEY_CONN_MODE = "relay_connection_mode"
        private const val KEY_CONN_CHOICE_MADE = "relay_connection_choice_made"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_LANGUAGE = "app_language_tag"
        private const val KEY_LANGUAGE_MIGRATED = "app_language_migrated"

        /**
         * Výchozí (zabudovaná) adresa oficiálního zero-knowledge relaye přes Tor.
         * Na tuhle appka míří, dokud si uživatel nepřepne na vlastní server.
         */
        const val DEFAULT_RELAY_URL =
            "http://m7rwivoh3yzvzbvi2m5ob3qau5wtik5f46qsphjycoyof7gcwfbjnaad.onion"

        /**
         * Výchozí přímá (clearnet) adresa oficiálního relaye přes Cloudflare tunel.
         * Použije se ve VÝCHOZÍM režimu při [ConnectionMode.CLOUDFLARE] - rychlé
         * připojení bez Toru, ale Cloudflare i server vidí IP klienta.
         */
        const val DEFAULT_RELAY_URL_CLOUDFLARE = "https://cryptochat.deersolutions.eu"
    }
}

/**
 * Způsob připojení k VÝCHOZÍMU relayi. [TOR] = přes zabudovaný Tor na .onion
 * (nejvyšší soukromí), [CLOUDFLARE] = napřímo přes https (rychlé, ale Cloudflare
 * i server vidí IP). Viz [SettingsRepository.getConnectionMode].
 */
enum class ConnectionMode { TOR, CLOUDFLARE }

/**
 * Kdy chat na pozadí šetří data. [AUTO] = jen na měřené síti (mobilní data),
 * [ALWAYS] = vždy, [NEVER] = nikdy (vždy plná rychlost). Pořadí = pořadí v
 * segmentovém přepínači v Nastavení. Viz [SettingsRepository.getSaverMode].
 */
enum class SaverMode { AUTO, ALWAYS, NEVER }
