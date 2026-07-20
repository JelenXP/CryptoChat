package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf

/**
 * Kompatibilita formátu zpráv mezi dvěma zařízeními.
 *
 * Formát obálky ([ChatEnvelope]) se časem mění. Bez tohohle modulu by starší a
 * novější appka jen tiše zahazovaly navzájem svoje zprávy - uživatel by viděl
 * jen to, že „zprávy nechodí", bez vysvětlení. Tady se to pojmenuje a řekne se,
 * kdo má co udělat.
 *
 * **Verze má dvě části a každá leží jinde - schválně:**
 *
 * - **MAJOR** ([WIRE_MAJOR]) je v OTEVŘENÉ hlavičce blobu (první bajt). Změna
 *   majoru znamená, že se zprávy vůbec nedají přečíst - a právě proto musí být
 *   čitelný i bez dešifrování. Kdyby byl uvnitř šifry, u nekompatibilní verze
 *   by se k němu nikdo nedostal, tedy přesně tam, kde je potřeba. Je zapečený
 *   v AAD, takže ho po cestě nejde přepsat (rozbilo by to GCM tag).
 *
 * - **MINOR** ([WIRE_MINOR]) je UVNITŘ šifry. Minor se mění, když zprávy pořád
 *   chodí, ale přibyla schopnost, kterou starší strana neumí (třeba jiný způsob
 *   posílání velkých souborů). Číst ho jde jen tehdy, když major sedí - a tehdy
 *   dešifrování funguje. Relayi tím nepřibude žádná další informace.
 *
 * Co relay uvidí: jedno číslo (major), stejné pro všechny uživatele dané verze.
 * Nerozlišuje lidi ani konverzace.
 */
object WireCompat {

    private const val TAG = "WireCompat"
    private const val PREFS_NAME = "crypto_chat_compat"

    /** Neznámá verze protějšku (ještě od něj nic nedorazilo). */
    const val UNKNOWN = -1

    /**
     * **Zvyš, když se zprávy přestanou dát přečíst** - změna AAD, rozložení
     * hlavičky, šifry, významu `kind`. Příjemce se starším majorem uvidí
     * „aplikace spolu nebudou fungovat, aktualizuj".
     *
     * Historie:
     *  - 1: původní formát (`IV || ciphertext`), bez AAD i bez bajtu verze.
     *       Nikdy nebyl veřejně vydaný.
     *  - 2: bajt majoru v hlavičce, minor uvnitř šifry, AAD se směrem schránky
     *       (`ccdir:<dir>|w:<major>`).
     */
    const val WIRE_MAJOR: Int = 2

    /**
     * **Zvyš, když přibude schopnost, ale zprávy chodí dál.** Starší strana
     * pořád všechno přečte, jen neumí novinku - uživateli se ukáže mírnější
     * hláška („některé funkce nemusí fungovat").
     *
     * Historie:
     *  - 1: text, fotka jedním blobem, soubory po kouscích (manifest + chunky).
     */
    const val WIRE_MINOR: Int = 1

    /** Jak si stojí protějšek oproti nám. */
    enum class Peer {
        /** Mluvíme stejným formátem (nebo o protějšku ještě nic nevíme). */
        OK,

        /** Starší MINOR: zprávy chodí, ale protějšek neumí nějakou novinku. */
        MINOR_OUTDATED,

        /** Novější MINOR: protějšek umí něco navíc, co my ještě ne. */
        MINOR_NEWER,

        /** Starší MAJOR: zprávy si navzájem nepřečtete. Aktualizovat musí ON. */
        MAJOR_OUTDATED,

        /** Novější MAJOR: zprávy si navzájem nepřečtete. Aktualizovat musíme MY. */
        MAJOR_NEWER;

        /** Je to zásadní neshoda (chat prakticky nefunguje)? */
        val isBreaking: Boolean get() = this == MAJOR_OUTDATED || this == MAJOR_NEWER
    }

    /** Zjištěná verze protějšku. [UNKNOWN], dokud od něj něco nedorazí. */
    data class PeerVersion(val major: Int = UNKNOWN, val minor: Int = UNKNOWN)

    /**
     * Verze protějšků podle kontaktu. `mutableStateMapOf`, aby na změnu rovnou
     * zareagovalo UI (banner v konverzaci) bez ručního obnovování.
     */
    private val versions = mutableStateMapOf<String, PeerVersion>()

    /** Zjištěná verze protějšku daného kontaktu. */
    fun peerVersion(context: Context, contactId: String): PeerVersion {
        versions[contactId]?.let { return it }
        val loaded = try {
            val p = prefs(context)
            PeerVersion(
                p.getInt(key(contactId, "major"), UNKNOWN),
                p.getInt(key(contactId, "minor"), UNKNOWN)
            )
        } catch (e: Exception) {
            PeerVersion()
        }
        versions[contactId] = loaded
        return loaded
    }

    /** Jak si stojí protějšek daného kontaktu (pro banner v konverzaci). */
    fun peerState(context: Context, contactId: String): Peer {
        val v = peerVersion(context, contactId)
        return when {
            v.major == UNKNOWN -> Peer.OK          // ještě nic nedorazilo
            v.major < WIRE_MAJOR -> Peer.MAJOR_OUTDATED
            v.major > WIRE_MAJOR -> Peer.MAJOR_NEWER
            v.minor == UNKNOWN -> Peer.OK
            v.minor < WIRE_MINOR -> Peer.MINOR_OUTDATED
            v.minor > WIRE_MINOR -> Peer.MINOR_NEWER
            else -> Peer.OK
        }
    }

    /**
     * Umí protějšek schopnost, která vznikla v daném minoru? Používej při
     * odesílání, ať se dá dopředu říct „tohle mu nedorazí" místo tichého selhání.
     *
     * Příklad budoucího použití: kdyby se posílání souborů nad 2 MB předělalo a
     * vyžadovalo minor 2, volalo by se `peerSupports(ctx, id, 2)` a při `false`
     * by se uživateli nabídlo, ať pošle menší soubor.
     *
     * Když o protějšku ještě nic nevíme, vrací true - neblokujeme kvůli domněnce.
     */
    fun peerSupports(context: Context, contactId: String, requiredMinor: Int): Boolean {
        val v = peerVersion(context, contactId)
        if (v.major == UNKNOWN || v.minor == UNKNOWN) return true
        return v.major >= WIRE_MAJOR && v.minor >= requiredMinor
    }

    /**
     * Vyhodnotí blob PŘED pokusem o dešifrování podle otevřeného majoru. Vrací
     * true, když má smysl blob zkoušet otevřít; false znamená nekompatibilní
     * major - blob zahoď a uživateli se ukáže vysvětlení.
     */
    fun acceptMajor(context: Context, contactId: String, blob: ByteArray): Boolean {
        val major = readMajor(blob) ?: (WIRE_MAJOR - 1)  // bez bajtu verze = starší formát
        if (major != WIRE_MAJOR) {
            // Minor u cizího majoru neznáme (nešlo by ho přečíst) - zahoď ho.
            remember(context, contactId, PeerVersion(major, UNKNOWN))
            return false
        }
        return true
    }

    /**
     * Zaznamená minor odesílatele. Volá se AŽ po úspěšném dešifrování - teprve
     * tehdy je jisté, že major sedí a minor je pravý (autentizovaný GCM tagem).
     */
    fun notePeerMinor(context: Context, contactId: String, minor: Int) {
        remember(context, contactId, PeerVersion(WIRE_MAJOR, minor))
    }

    /** Zapomene verzi kontaktu (při jeho smazání). */
    fun clear(context: Context, contactId: String) {
        versions.remove(contactId)
        try {
            prefs(context).edit()
                .remove(key(contactId, "major"))
                .remove(key(contactId, "minor"))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Úklid stavu kompatibility selhal", e)
        }
    }

    /**
     * Major z otevřené hlavičky blobu, nebo null když blob na náš formát vůbec
     * nevypadá (prázdný, nebo nulový první bajt). Starší formát začínal náhodným
     * IV, takže se sem občas trefí - ale ten stejně nejde rozšifrovat, takže
     * výsledek („nekompatibilní") je správný tak jako tak.
     */
    fun readMajor(blob: ByteArray): Int? {
        if (blob.isEmpty()) return null
        val v = blob[0].toInt() and 0xFF
        return if (v == 0) null else v
    }

    private fun remember(context: Context, contactId: String, version: PeerVersion) {
        if (versions[contactId] == version) return
        versions[contactId] = version
        try {
            prefs(context).edit()
                .putInt(key(contactId, "major"), version.major)
                .putInt(key(contactId, "minor"), version.minor)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Uložení stavu kompatibility selhalo", e)
        }
    }

    private fun key(contactId: String, part: String) = "compat_${part}_$contactId"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
