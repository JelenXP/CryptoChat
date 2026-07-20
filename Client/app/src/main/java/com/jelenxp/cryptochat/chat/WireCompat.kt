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
 *
 * ## Pravidla pro přidávání novinek (PLATÍ NAVŽDY)
 *
 * Tahle pravidla existují proto, aby starší verze appky novinku **přehlédla**
 * místo aby o zprávu přišla. Dřív to takhle nešlo: cokoli nového znamenalo nový
 * `kind`, který starší appka neuměla zařadit, a blob skončil na 30 dní
 * v [BlobQuarantine].
 *
 *  1. **`kind` je zmrazený.** Hodnoty 0-3 jsou trvalá nosná sada. Nikdy
 *     nepřidávej další - je to jediná změna, kterou starší verze nepřežije.
 *  2. **Všechno nové jede v traileru** ([WireExt]), za datovou oblastí, kam
 *     starší parser nekouká.
 *  3. **Každá novinka si zvolí strategii degradace:**
 *     - *ENRICH* - ozdoba normální zprávy (odpověď, formátování). Starší verze
 *       ukáže obyčejnou zprávu. Nic dalšího není potřeba.
 *     - *CONTROL* - řídicí zpráva bez obsahu pro uživatele (reakce, potvrzení
 *       o přečtení). **Musí mít prázdnou datovou oblast** (viz [WireExt.Control]);
 *       verze, která tu funkci nezná, ji pak tiše zahodí.
 *     - *SUPPRESS* - protějšku, který to neumí, se to neposílá vůbec
 *       (viz [peerSupports], u řídicích zpráv [peerKnownSupports]).
 *
 *     Řídicí zprávu s neprázdným tělem **nemá smysl posílat**: příjemce ji
 *     podle prázdnosti těla rozpoznává, takže by ji s obsahem zpracoval jako
 *     běžnou zprávu. Když chceš, aby starší verze místo ticha něco viděla,
 *     pošli normální zprávu zvlášť - ne řídicí s náhradním textem.
 *  4. **Neznámé TLV se přeskakuje**, poškozený trailer se ignoruje a zpráva
 *     s obsahem se nikdy nezahazuje - kvůli ozdobě se zpráva nesmí ztratit.
 *     Neznámý `kind` jde do karantény (novější verze ho možná přečte), NE
 *     k zahození.
 *  5. **Čísla typů a `feature id` se nerecyklují** (registr ve [WireExt]).
 *  6. **Zvyš [WIRE_MINOR]** a dopiš řádek do jeho historie.
 *  7. **Přidej zamražený vzorek do testů** (`ChatEnvelopeGoldenTest`). Roundtrip
 *     test rozbití formátu NECHYTÍ - zapečetí i otevře stejným kódem.
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
     *  - 2: bajt verze v hlavičce, AAD se směrem schránky (`ccdir:<dir>|w:<major>`),
     *       vnitřní hlavička 13 B (`[1B kind][8B ts][4B délka]`).
     *  - 3: vnitřní hlavička 14 B - přibyl bajt minor na pozici 1.
     *
     * POZOR na past, která už jednou nastala: verze 2 a 3 se liší JEN vnitřním
     * rozložením. Když se major nezvýší, blob se úspěšně dešifruje (AAD sedí),
     * ale rozparsuje se posunutě, `open()` vrátí null a zpráva se tiše zahodí -
     * a relay ji mezitím smazal. Rozložení hlavičky JE součástí kontraktu.
     */
    const val WIRE_MAJOR: Int = 3

    /**
     * **Zvyš, když přibude schopnost, ale zprávy chodí dál.** Starší strana
     * pořád všechno přečte, jen neumí novinku - uživateli se ukáže mírnější
     * hláška („některé funkce nemusí fungovat").
     *
     * Historie:
     *  - 1: text, fotka jedním blobem, soubory po kouscích (manifest + chunky).
     *  - 2: rozšiřující trailer ([WireExt]) - obecný mechanismus pro novinky,
     *       stabilní ID zprávy (MSG_ID) u textu a fotek, řídicí zprávy
     *       (CONTROL) a odlišení „neumím to" od „nejde dešifrovat".
     *  - 3: odpovědi na zprávu (REPLY_TO, strategie ENRICH - starší verze ukáže
     *       obyčejnou zprávu) a reakce emoji (CONTROL/REACTION, strategie
     *       CONTROL + SUPPRESS vůči minoru 1).
     */
    const val WIRE_MINOR: Int = 3

    /** Minor, od kterého protějšek umí reakce ZOBRAZIT (v1.2). */
    const val MINOR_REACTIONS = 3

    /**
     * Minor, od kterého je BEZPEČNÉ poslat protějšku řídicí zprávu (reakci),
     * i když ji neumí zobrazit.
     *
     * Od minoru 2 (v1.1) protějšek zná rozšiřující trailer a řídicí zprávy:
     * neznámou řídicí zprávu s prázdným tělem **tiše zahodí** (viz [ChatEnvelope]
     * a jeho `Result.Unsupported`). Poslat mu reakci je tedy bez následků -
     * u sebe ji vidím, on ji jen zahodí. **Proto se reakce gatují právě tímhle
     * minorem, ne [MINOR_REACTIONS].**
     *
     * Minor 1 (v1.0) o traileru neví a četl by jen `len` bajtů dat - u reakce 0,
     * takže by mu naskočila prázdná bublina. Tam se reakce posílat NESMÍ.
     */
    const val MINOR_CONTROL_SAFE = 2

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
     * Jako [peerSupports], ale při NEZNÁMÉ verzi vrací **false**.
     *
     * Použij tam, kde by omyl uživateli něco pokazil - typicky u řídicích zpráv
     * (strategie SUPPRESS). Reakce poslaná protějšku s minorem 1 by se u něj
     * ukázala jako prázdná bublina, protože o traileru neví; radši ji tedy
     * neposlat, dokud si nejsme jistí.
     *
     * V praxi to nic neomezí: reagovat jde jen na zprávu, která už dorazila,
     * a tou se verze protějšku právě dozvěděla.
     */
    fun peerKnownSupports(context: Context, contactId: String, requiredMinor: Int): Boolean {
        val v = peerVersion(context, contactId)
        if (v.major == UNKNOWN || v.minor == UNKNOWN) return false
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
            // POZOR: tenhle bajt NENÍ autentizovaný (u cizího majoru neumíme
            // ověřit GCM tag). Nedůvěryhodný relay by tedy mohl podstrčit blob
            // s falešnou verzí a natrvalo uživateli zobrazit „aktualizuj si
            // appku" - pěkný předstupeň sociálního inženýrství. Držíme ho proto
            // jen v paměti (do restartu procesu) a NEukládáme na disk.
            rememberEphemeral(contactId, PeerVersion(major, UNKNOWN))
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

    /**
     * Zapamatuje verzi jen v paměti (neautentizovaný zdroj - viz [acceptMajor]).
     *
     * **Minor se přitom NEPŘEPISUJE.** Ten se dá zjistit jen z úspěšně
     * dešifrovaného blobu, takže je důvěryhodný; major z otevřené hlavičky
     * důvěryhodný není. Kdyby ho přepsal na UNKNOWN, stačil by relayi jediný
     * podvržený bajt, aby appka zapomněla, co protějšek umí - a reakce by se
     * přestaly posílat až do restartu procesu.
     */
    private fun rememberEphemeral(contactId: String, version: PeerVersion) {
        val knownMinor = versions[contactId]?.minor ?: UNKNOWN
        versions[contactId] = version.copy(
            minor = if (version.minor == UNKNOWN) knownMinor else version.minor
        )
    }

    /** Zapamatuje a ULOŽÍ verzi. Jen z autentizovaného zdroje (po dešifrování). */
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
