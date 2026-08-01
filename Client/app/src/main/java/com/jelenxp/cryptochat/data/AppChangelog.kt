package com.jelenxp.cryptochat.data

import com.jelenxp.cryptochat.R

/**
 * Novinky posledních (max [MAX_ENTRIES]) verzí zabudované do appky. Text každé
 * verze je v resources (dvojjazyčně přes `strings.xml`), takže „Novinky" fungují
 * offline a nezávisí na síti.
 *
 * Dvě použití:
 *  - **Nastavení → Novinky** ukáže celý seznam ([ENTRIES]).
 *  - **Jednorázový překryv po aktualizaci** ukáže jen verze novější než ta, kterou
 *    uživatel naposledy viděl ([entriesNewerThanCode]). Díky tomu člověk, který
 *    přeskočí verzi (např. z 2.2.0 rovnou na bugfix 3.0.1 bez 3.0.0), pořád uvidí
 *    novinky přeskočené hlavní verze - ne jen changelog toho posledního bugfixu.
 *
 * **Řadí se podle `versionCode`, ne podle jména verze.** versionCode je monotónní
 * (roste o 1 při každém vydání) a appka ho o naposledy viděné verzi drží pro
 * KAŽDÉHO uživatele už dnes ([SettingsRepository.getLastSeenVersionCode]), takže
 * „co je novější než naposledy viděné" funguje i pro existující instalace bez
 * migrace.
 *
 * **Údržba při vydání:** přidej nový `changelog_<ver>` řetězec do OBOU `strings.xml`,
 * vlož nový [Entry] na ZAČÁTEK [ENTRIES] a nejstarší (šestý) odeber - drží se
 * posledních pět. Když sáhneš sem, spusť `AppChangelogTest`.
 */
object AppChangelog {

    /** Kolik posledních verzí se drží. */
    const val MAX_ENTRIES = 5

    /**
     * Jeden záznam novinek.
     * @param version marketingová verze (`versionName`, např. „2.2.0") - jen pro nadpis.
     * @param versionCode monotónní kód sestavení té verze - podle něj se řadí a
     *   porovnává „novější než naposledy viděné".
     * @param bodyRes string resource s textem novinek (dvojjazyčně).
     */
    data class Entry(val version: String, val versionCode: Int, val bodyRes: Int)

    /** Posledních [MAX_ENTRIES] verzí, NEJNOVĚJŠÍ první. */
    val ENTRIES: List<Entry> = listOf(
        Entry("2.7.0", 19, R.string.changelog_2_7_0),
        Entry("2.6.1", 18, R.string.changelog_2_6_1),
        Entry("2.6.0", 17, R.string.changelog_2_6_0),
        Entry("2.5.0", 16, R.string.changelog_2_5_0),
        Entry("2.4.1", 15, R.string.changelog_2_4_1),
    )

    /**
     * Záznamy novější než daný `versionCode` (nejnovější první). Prázdné = uživatel
     * už tu nejnovější viděl. `0` (čerstvá instalace) vrátí vše.
     */
    fun entriesNewerThanCode(lastSeenVersionCode: Int): List<Entry> =
        ENTRIES.filter { it.versionCode > lastSeenVersionCode }
}
