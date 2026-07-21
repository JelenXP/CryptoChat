package com.jelenxp.cryptochat.ui.util

/**
 * Mapování mezi UI volbou jazyka („system"/„cs"/„en") a BCP-47 tagem, který drží
 * [AppLocale]. Čistá logika - **vytažená z UI, aby šla otestovat** (viz testovací
 * politika v `CLAUDE.md`). Prázdný tag = jazyk systému.
 */
object LanguageMap {
    const val SYSTEM = "system"
    const val CZECH = "cs"
    const val ENGLISH = "en"

    /** UI volba → tag pro [AppLocale] (prázdný = jazyk systému). */
    fun choiceToTag(choice: String): String = when (choice) {
        CZECH -> "cs"
        ENGLISH -> "en"
        else -> ""
    }

    /** Tag → UI volba (prázdný nebo neznámý = systém). */
    fun tagToChoice(tag: String): String = when (tag) {
        "cs" -> CZECH
        "en" -> ENGLISH
        else -> SYSTEM
    }
}
