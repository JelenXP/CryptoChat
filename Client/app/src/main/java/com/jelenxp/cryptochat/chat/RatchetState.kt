package com.jelenxp.cryptochat.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Perzistentní stav **Double Ratchetu** pro JEDEN kontakt.
 *
 * Toto je čistě datový nosič - žádná krypto logika (ta přijde v [DoubleRatchet]
 * ve Fázi 2). Ukládá ho [RatchetStore] šifrovaně přes [com.jelenxp.cryptochat.crypto.StorageCrypto]
 * (at rest v Android Keystore), do zálohy jde v otevřené podobě uvnitř heslem
 * šifrovaného balíčku ([com.jelenxp.cryptochat.data.ContactBackup], verze 3).
 *
 * **Tajné hodnoty se drží jako base64 řetězce**, ne `ByteArray` - schválně:
 *  - `data class` s `ByteArray` porovnává pole referenčně, takže by roundtrip
 *    testy i cache selhávaly; se `String` funguje `equals` správně,
 *  - serializace do JSON je pak triviální,
 *  - je to konzistentní s tím, jak klíč drží i [com.jelenxp.cryptochat.data.Contact.keyBase64].
 *  Kompromis (klíče ve `String` nejdou z paměti vynulovat) je shodný se zbytkem
 *  appky a vědomě přijatý.
 *
 * **Model se ještě bude ve Fázích 2-4 rozrůstat.** Proto je serializace
 * [fromJson] záměrně tolerantní: chybějící pole se doplní výchozí hodnotou,
 * neznámá se ignorují - starší i novější záloha se tak vždy načte.
 *
 * ## Význam polí
 *
 * - **root/řetězy** ([rootKeyB64], [sendChainKeyB64], [recvChainKeyB64]) -
 *   kořen ratchetu a symetrické řetězy pro každý směr (forward secrecy per zpráva).
 * - **epochy a pořadí** ([sendEpoch]/[recvEpoch], [sendMsgNo]/[recvMsgNo]) -
 *   epocha řídí adresu schránky (Návrh 2), pořadí vybírá klíč zprávy uvnitř epochy.
 *   [msgsSinceEpoch] je čítač pravidla „posuň epochu po každých K odeslaných
 *   zprávách" (bez hodin).
 * - **KEM ratchet** ([selfKemPublicB64]/[selfKemPrivateB64], [peerKemPublicB64]) -
 *   materiál pro post-compromise uzdravení. [retainedKemPrivatesB64] drží pár
 *   posledních vlastních privátů, dokud není jisté, že protějšek přešel na nový
 *   pubkey (jinak by nešlo dekapsulovat - viz plán, bug oblast „retence privátů").
 * - **[skipped]** - přeskočené klíče zpráv (out-of-order + idempotence příjmu).
 * - **[inboundMarker]/[pointerMarker]** - monotónní čítače (NE hodiny) pro
 *   detekci jednosměrného ticha a rate-limit beacon pointeru.
 * - **[mode]** - fáze migrace ze statického klíče na ratchet.
 */
data class RatchetState(
    val rootKeyB64: String,
    val sendChainKeyB64: String?,
    val recvChainKeyB64: String?,
    val sendEpoch: Int,
    val sendMsgNo: Int,
    val msgsSinceEpoch: Int,
    val recvEpoch: Int,
    val recvMsgNo: Int,
    val selfKemPublicB64: String?,
    val selfKemPrivateB64: String?,
    val retainedKemPrivatesB64: List<String>,
    val peerKemPublicB64: String?,
    val skipped: List<SkippedMessageKey>,
    val inboundMarker: Long,
    val pointerMarker: Long,
    val mode: Mode,
    /**
     * Krypto generace (Fáze 4, PCS). Roste o 1 při každém dokončeném KEM re-key
     * (viz [com.jelenxp.cryptochat.chat.DoubleRatchet.applyRekey]). Kořen i oba
     * řetěze pak vzniknou z čerstvé entropie a `sendMsgNo`/`recvMsgNo` (= index
     * uvnitř generace) se resetují na 0. Mění ji JEN re-key (dotýká se obou půlek
     * stavu → aplikuje se pod plným zámkem, ne přes withSend/withRecv). Gen 0 =
     * Fáze 3 (bez KEM kroku), bajtově beze změny.
     */
    val generation: Int = 0,
    /**
     * PŘEDCHOZÍ generace přijímacího řetězu (grace, Fáze 4). Po re-key sem
     * [com.jelenxp.cryptochat.chat.DoubleRatchet.applyRekey] uloží dosavadní
     * přijímací řetěz (klíč + pozici), aby se in-order konec předchozí generace,
     * co dorazí přes relay AŽ po re-key, ještě dal dešifrovat (jinak by se tiše
     * ztratil - odesílatel ho poslal ještě před re-key). Drží se JEDNA předchozí
     * generace; starší už jen ze skipped-store, jinak se zahodí.
     */
    val prevRecvChainKeyB64: String? = null,
    val prevRecvGeneration: Int = -1,
    val prevRecvMsgNo: Int = 0,
    /**
     * Přechodný stav re-key HANDSHAKE (Fáze 4b). Perzistentní, ať handshake přežije
     * restart. [rekeyStage]: 0=žádný, 1=iniciátor odeslal OFFER (čeká ACCEPT),
     * 2=iniciátor odeslal CONFIRM (čeká na protějškovu novou generaci → pak přesejne
     * pomocí [rekeySsB64]), 3=odpovídající odeslal ACCEPT (čeká CONFIRM).
     * [rekeyPrivB64] = můj ML-KEM privát k pubkey, který jsem poslal.
     * [rekeySsB64] = iniciátor: hotové kombinované tajemství čekající na přesejnutí;
     * odpovídající: dílčí ssI mezi ACCEPT a CONFIRM.
     */
    val rekeyId: String? = null,
    val rekeyPrivB64: String? = null,
    val rekeySsB64: String? = null,
    val rekeyStage: Int = 0
) {

    /** Fáze re-key handshake ([rekeyStage]). */
    object Rekey {
        const val NONE = 0
        const val INIT_OFFERED = 1
        const val INIT_CONFIRMED = 2
        const val RESP_ACCEPTED = 3
    }

    /**
     * Fáze migrace ze statického klíče kontaktu na Double Ratchet.
     *  - [LEGACY]  - ještě jedeme po dnešní denní schránce + statickém klíči.
     *  - [DUAL]    - přechod: posloucháme legacy i ratchet, odesíláme legacy,
     *                dokud se nepotvrdí, že protějšek ratchet přijímá.
     *  - [RATCHET] - plně na ratchetu.
     */
    enum class Mode { LEGACY, DUAL, RATCHET }

    /**
     * Sloučí ODESÍLACÍ půlku z [s] do tohoto stavu (přijímací nechá beze změny).
     * Send a recv pole jsou disjunktní, takže souběžný posun odesílání a příjmu
     * si nesmí navzájem přepsat stav (jinak by se `sendMsgNo` vrátil a opakoval
     * by se GCM klíč). V Fázi 3 jsou root/KEM/mode konstantní (mění je až KEM krok
     * ve Fázi 4 - ten se dotýká OBOU půlek a bude potřeba řešit zvlášť).
     */
    fun withSendFrom(s: RatchetState): RatchetState = copy(
        sendChainKeyB64 = s.sendChainKeyB64,
        sendEpoch = s.sendEpoch,
        sendMsgNo = s.sendMsgNo,
        msgsSinceEpoch = s.msgsSinceEpoch,
        pointerMarker = s.pointerMarker
    )

    /** Sloučí PŘIJÍMACÍ půlku z [s] (odesílací nechá beze změny). Viz [withSendFrom]. */
    fun withRecvFrom(s: RatchetState): RatchetState = copy(
        recvChainKeyB64 = s.recvChainKeyB64,
        recvEpoch = s.recvEpoch,
        recvMsgNo = s.recvMsgNo,
        skipped = s.skipped,
        inboundMarker = s.inboundMarker,
        prevRecvChainKeyB64 = s.prevRecvChainKeyB64,
        prevRecvMsgNo = s.prevRecvMsgNo
    )

    /** Serializace do JSON (pro [RatchetStore] i zálohu). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("sv", STATE_VERSION)
        put("rk", rootKeyB64)
        sendChainKeyB64?.let { put("cks", it) }
        recvChainKeyB64?.let { put("ckr", it) }
        put("es", sendEpoch)
        put("ns", sendMsgNo)
        put("mse", msgsSinceEpoch)
        put("er", recvEpoch)
        put("nr", recvMsgNo)
        selfKemPublicB64?.let { put("kpub", it) }
        selfKemPrivateB64?.let { put("kpriv", it) }
        if (retainedKemPrivatesB64.isNotEmpty()) {
            put("kret", JSONArray(retainedKemPrivatesB64))
        }
        peerKemPublicB64?.let { put("ppub", it) }
        if (skipped.isNotEmpty()) {
            val arr = JSONArray()
            skipped.forEach { arr.put(it.toJson()) }
            put("skip", arr)
        }
        put("im", inboundMarker)
        put("pm", pointerMarker)
        put("mode", mode.name)
        if (generation != 0) put("gen", generation)
        prevRecvChainKeyB64?.let { put("pck", it) }
        if (prevRecvGeneration != -1) put("pg", prevRecvGeneration)
        if (prevRecvMsgNo != 0) put("pn", prevRecvMsgNo)
        rekeyId?.let { put("rkid", it) }
        rekeyPrivB64?.let { put("rkpriv", it) }
        rekeySsB64?.let { put("rkss", it) }
        if (rekeyStage != 0) put("rkst", rekeyStage)
    }

    companion object {
        /** Verze serializace stavu (roste, kdyby se formát změnil nekompatibilně). */
        const val STATE_VERSION = 1

        /**
         * Načte stav z JSON. Tolerantní: chybějící pole → výchozí hodnota, neznámá
         * pole se ignorují. Vrací `null` jen když chybí povinný kořen ([rootKeyB64])
         * nebo je JSON úplně mimo - tedy když stav prostě nejde použít.
         */
        fun fromJson(o: JSONObject): RatchetState? {
            val rk = o.optStringOrNull("rk") ?: return null
            val skip = ArrayList<SkippedMessageKey>()
            o.optJSONArray("skip")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val so = arr.optJSONObject(i) ?: continue
                    SkippedMessageKey.fromJson(so)?.let { skip.add(it) }
                }
            }
            val retained = ArrayList<String>()
            o.optJSONArray("kret")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { retained.add(it) }
                }
            }
            val mode = runCatching { Mode.valueOf(o.optString("mode", Mode.LEGACY.name)) }
                .getOrDefault(Mode.LEGACY)
            return RatchetState(
                rootKeyB64 = rk,
                sendChainKeyB64 = o.optStringOrNull("cks"),
                recvChainKeyB64 = o.optStringOrNull("ckr"),
                sendEpoch = o.optInt("es", 0),
                sendMsgNo = o.optInt("ns", 0),
                msgsSinceEpoch = o.optInt("mse", 0),
                recvEpoch = o.optInt("er", 0),
                recvMsgNo = o.optInt("nr", 0),
                selfKemPublicB64 = o.optStringOrNull("kpub"),
                selfKemPrivateB64 = o.optStringOrNull("kpriv"),
                retainedKemPrivatesB64 = retained,
                peerKemPublicB64 = o.optStringOrNull("ppub"),
                skipped = skip,
                inboundMarker = o.optLong("im", 0),
                pointerMarker = o.optLong("pm", 0),
                mode = mode,
                generation = o.optInt("gen", 0),
                prevRecvChainKeyB64 = o.optStringOrNull("pck"),
                prevRecvGeneration = o.optInt("pg", -1),
                prevRecvMsgNo = o.optInt("pn", 0),
                rekeyId = o.optStringOrNull("rkid"),
                rekeyPrivB64 = o.optStringOrNull("rkpriv"),
                rekeySsB64 = o.optStringOrNull("rkss"),
                rekeyStage = o.optInt("rkst", 0)
            )
        }
    }
}

/**
 * Jeden přeskočený (nebo už spotřebovaný) klíč zprávy: umožňuje dešifrovat
 * zprávy, které dorazí mimo pořadí, a činí příjem **idempotentním** (reprocessing
 * téhož blobu po neúspěšném ACK klíč najde místo aby posouval řetěz znovu).
 *
 * [marker] je monotónní pořadí uložení (NE hodiny) - podle něj se úložiště
 * ořezává, když přeroste strop.
 */
data class SkippedMessageKey(
    val epoch: Int,
    val msgNo: Int,
    val messageKeyB64: String,
    val marker: Long,
    /** Krypto generace, do které klíč patří (Fáze 4). Matchuje se (generation, msgNo). */
    val generation: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("e", epoch)
        .put("n", msgNo)
        .put("mk", messageKeyB64)
        .put("m", marker)
        .apply { if (generation != 0) put("g", generation) }

    companion object {
        fun fromJson(o: JSONObject): SkippedMessageKey? {
            val mk = o.optStringOrNull("mk") ?: return null
            return SkippedMessageKey(
                epoch = o.optInt("e", 0),
                msgNo = o.optInt("n", 0),
                messageKeyB64 = mk,
                marker = o.optLong("m", 0),
                generation = o.optInt("g", 0)
            )
        }
    }
}

/** `optString`, který pro chybějící / prázdné / `null` pole vrátí `null` (ne ""). */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
