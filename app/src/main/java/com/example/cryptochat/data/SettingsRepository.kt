package com.example.cryptochat.data

import android.content.Context

/** Jednoduché úložiště nastavení appky (zámek appky + povolení kopírování klíče). */
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

    companion object {
        private const val PREFS_NAME = "crypto_chat_settings"
        private const val KEY_APP_LOCK = "app_lock_enabled"
        private const val KEY_KEY_COPY = "key_copy_allowed"
    }
}
