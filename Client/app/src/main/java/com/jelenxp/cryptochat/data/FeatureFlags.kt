package com.jelenxp.cryptochat.data

/**
 * Centrální vypínače funkcí, které se dají zapnout/vypnout na jednom místě.
 * Umožňuje mít feature v kódu hotovou, ale dočasně skrytou/neaktivní.
 */
object FeatureFlags {

    /**
     * Kontrola nových verzí (GitHub Releases) + související UI v Nastavení.
     *
     * Míří na veřejný repozitář, kam se nahrávají jen vydaná APK
     * (viz `UpdateChecker.RELEASES_URL`) - samotný kód chat appky zůstává privátní.
     */
    const val UPDATE_CHECK_ENABLED = true
}
