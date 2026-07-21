package com.jelenxp.cryptochat.ui.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Jazyk aplikace přepínaný ZA BĚHU, **bez recreate Activity**.
 *
 * Dřív se jazyk měnil přes `AppCompatDelegate.setApplicationLocales`, což Activity
 * RECREATOVALO - odtud „poblikání" při změně jazyka a znovuvyžádání zámku appky
 * (jeho stav se recreatem zahodil). Teď jazyk drží procesový stav [tag] a UI ho
 * čte přes obalený `Context`, takže se přepne IN-PLACE (jen rekompozice). Zámek
 * (a jakýkoli jiný stav obrazovek) tím pádem zůstává.
 *
 * Notifikace běží mimo Compose, proto si `Context` obalují samy přes
 * [localizedContext] (podle uloženého tagu v `SettingsRepository`).
 */
object AppLocale {
    /** "cs" / "en" / "" (jazyk systému). Compose-observable, žije po celý proces. */
    var tag by mutableStateOf("")
}

/**
 * `Context` s prosazeným jazykem [tag] (prázdný = beze změny). ZÁMĚRNĚ ponechává
 * původní `Context` v řetězci `baseContext` - `BiometricPrompt` hledá
 * `FragmentActivity` právě přes `baseContext` - a jen přepíše `resources` na
 * lokalizované. Ostatní volání (getSystemService, SharedPreferences, startActivity)
 * delegují na base beze změny.
 */
fun localizedContext(base: Context, tag: String): Context {
    if (tag.isBlank()) return base
    // Zkopíruj CELOU konfiguraci a přepiš jen jazyk - density, noční režim atd.
    // musí zůstat, jinak by se rozbily nezávislé věci (barvy/rozměry).
    val config = Configuration(base.resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    val localized = base.createConfigurationContext(config)
    return object : ContextWrapper(base) {
        override fun getResources(): Resources = localized.resources
    }
}

/**
 * Obalí obsah appky lokalizovaným `Context`em/`Configuration` podle [AppLocale.tag].
 * Změna [AppLocale.tag] jen rekomponuje - žádný recreate, žádné poblikání.
 */
@Composable
fun LocalizedApp(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val tag = AppLocale.tag
    val localized = remember(base, tag) { localizedContext(base, tag) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content
    )
}
