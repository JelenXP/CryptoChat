package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf

/**
 * Kompatibilita formátu zpráv mezi dvěma zařízeními.
 *
 * Formát obálky ([ChatEnvelope]) se časem mění. Bez tohohle modulu by starší a
 * novější appka jen tiše zahazovaly navzájem svoje zprávy: dešifrování selže na
 * GCM tagu a blob se zahodí, takže by uživatel viděl jen to, že „zprávy nechodí"
 * - bez jakéhokoli vysvětlení. Tady se to pojmenuje a řekne se, kdo má co udělat.
 *
 * **Jak to funguje:** každý blob začíná JEDNÍM otevřeným bajtem s verzí formátu
 * ([WIRE_VERSION]). Musí být čitelný i tehdy, když obsah rozšifrovat nejde -
 * kdyby byl uvnitř šifry, u nekompatibilní verze by se k němu nikdo nedostal,
 * tedy přesně tam, kde je potřeba.
 *
 * **Co to prozradí relayi:** jedno číslo, které je stejné pro všechny uživatele
 * dané verze appky. Nerozlišuje lidi ani konverzace. Navíc je zapečené v AAD
 * obálky, takže ho po cestě nejde přepsat - manipulace rozbije GCM tag a zpráva
 * se zahodí (relay stejně může zprávy zahazovat i bez toho).
 */
object WireCompat {

    private const val TAG = "WireCompat"
    private const val PREFS_NAME = "crypto_chat_compat"

    /**
     * Verze formátu obálky. **Zvyš ji vždy, když se změní cokoli, co druhá
     * strana musí umět přečíst** - obsah AAD, rozložení hlavičky, význam `kind`.
     *
     * Historie:
     *  - 1: původní formát (`IV || ciphertext`), bez AAD a bez bajtu verze.
     *       Nikdy nebyl veřejně vydaný.
     *  - 2: přidán tenhle bajt verze a AAD se směrem schránky (`ccdir:<dir>|w:<verze>`).
     */
    const val WIRE_VERSION: Int = 2

    /** Jak si stojí protějšek oproti nám. */
    enum class Peer {
        /** Mluvíme stejným formátem. */
        OK,

        /** Protějšek má STARŠÍ verzi - musí si aktualizovat appku on. */
        OUTDATED,

        /** Protějšek má NOVĚJŠÍ verzi - musíme si aktualizovat appku my. */
        NEWER
    }

    /**
     * Stav podle kontaktu. `mutableStateMapOf`, aby na změnu rovnou zareagovalo
     * UI (banner v konverzaci) bez nutnosti cokoli ručně obnovovat.
     */
    private val states = mutableStateMapOf<String, Peer>()

    /** Jak si stojí protějšek daného kontaktu. */
    fun peerState(context: Context, contactId: String): Peer {
        states[contactId]?.let { return it }
        val stored = try {
            prefs(context).getInt(key(contactId), Peer.OK.ordinal)
        } catch (e: Exception) {
            Peer.OK.ordinal
        }
        val state = Peer.entries.getOrElse(stored) { Peer.OK }
        states[contactId] = state
        return state
    }

    /**
     * Vyhodnotí přijatý blob PŘED pokusem o dešifrování a zaznamená, jak si
     * protějšek stojí. Vrací true, když je blob v našem formátu (má smysl ho
     * zkoušet otevřít); false znamená nekompatibilní verzi - blob zahoď a
     * uživateli se ukáže vysvětlení.
     */
    fun accept(context: Context, contactId: String, blob: ByteArray): Boolean {
        val version = readVersion(blob)
        val state = when {
            version == WIRE_VERSION -> Peer.OK
            // Neznámý/chybějící bajt verze = zpráva ze starší verze, která ho
            // ještě neposílala. Tam nejde poznat konkrétní číslo, ale víme, že
            // je starší než my.
            version == null || version < WIRE_VERSION -> Peer.OUTDATED
            else -> Peer.NEWER
        }
        remember(context, contactId, state)
        return state == Peer.OK
    }

    /**
     * Potvrdí, že se zpráva opravdu podařilo otevřít - tedy že jsme kompatibilní.
     * Volá se až po úspěšném dešifrování, protože samotný bajt verze na to nestačí
     * (mohl by být poškozený).
     */
    fun markCompatible(context: Context, contactId: String) {
        remember(context, contactId, Peer.OK)
    }

    /** Zapomene stav kontaktu (při jeho smazání). */
    fun clear(context: Context, contactId: String) {
        states.remove(contactId)
        try {
            prefs(context).edit().remove(key(contactId)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid stavu kompatibility selhal", e)
        }
    }

    /**
     * Verze formátu z otevřené hlavičky blobu, nebo null když blob na náš formát
     * vůbec nevypadá (příliš krátký, nebo první bajt není platná verze).
     */
    fun readVersion(blob: ByteArray): Int? {
        if (blob.isEmpty()) return null
        val v = blob[0].toInt() and 0xFF
        // Verze 0 nedává smysl a hodnoty od 1 výš berem jako verzi. Starší
        // formát (bez bajtu verze) začínal náhodným IV, takže se sem občas
        // trefí - ale ten stejně nejde rozšifrovat, takže výsledek („nekompatibilní")
        // je správný tak jako tak.
        return if (v == 0) null else v
    }

    private fun remember(context: Context, contactId: String, state: Peer) {
        if (states[contactId] == state) return
        states[contactId] = state
        try {
            prefs(context).edit().putInt(key(contactId), state.ordinal).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Uložení stavu kompatibility selhalo", e)
        }
    }

    private fun key(contactId: String) = "compat_$contactId"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
