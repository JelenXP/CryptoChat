package com.jelenxp.cryptochat.chat

import android.content.Context
import android.net.ConnectivityManager
import com.jelenxp.cryptochat.data.SaverMode
import com.jelenxp.cryptochat.data.SettingsRepository

/**
 * Profil síťové aktivity na pozadí. [FULL] = plná rychlost (kratší intervaly,
 * nejnižší zpoždění), [SAVER] = úspora dat (delší long-poll drží spojení místo
 * častého znovunavazování, delší backoff, žádná kontrola aktualizací na síti).
 *
 * Profil se čte ČERSTVÝ při každém cyklu poll smyčky, takže přepnutí sítě
 * (WiFi ↔ mobilní data) se projeví hned v dalším cyklu - služba už změny sítě
 * sleduje přes NetworkCallback.
 */
enum class NetworkProfile { FULL, SAVER }

/**
 * Čistá rozhodovací funkce (bez Androidu, plně testovatelná): z měřenosti sítě
 * a volby uživatele určí profil.
 *
 * V režimu [SaverMode.AUTO] se dívá JEN na měřenou síť ([ConnectivityManager.isActiveNetworkMetered],
 * tj. mobilní data / měřená WiFi). Data Saver ani spořič baterie se ZÁMĚRNĚ
 * neřeší - uživatel si je řídí sám a míchat je do detekce by dělalo zážitek
 * nepředvídatelným.
 */
fun decideNetworkProfile(metered: Boolean, mode: SaverMode): NetworkProfile = when (mode) {
    SaverMode.NEVER -> NetworkProfile.FULL
    SaverMode.ALWAYS -> NetworkProfile.SAVER
    SaverMode.AUTO -> if (metered) NetworkProfile.SAVER else NetworkProfile.FULL
}

/**
 * Tenká obálka nad Androidem: přečte měřenost aktivní sítě + uloženou volbu a
 * vrátí profil. Při chybě čtení sítě raději [NetworkProfile.FULL] - neomezovat
 * příjem kvůli domněnce (delší long-poll na neznámé síti by mohl tiše utnout
 * spojení). Netestuje se jednotkově (Android API); logiku drží [decideNetworkProfile].
 */
fun currentNetworkProfile(context: Context): NetworkProfile {
    val metered = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.isActiveNetworkMetered ?: false
    } catch (e: Exception) {
        false
    }
    return decideNetworkProfile(metered, SettingsRepository(context).getSaverMode())
}
