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
    val mode: Mode
) {

    /**
     * Fáze migrace ze statického klíče kontaktu na Double Ratchet.
     *  - [LEGACY]  - ještě jedeme po dnešní denní schránce + statickém klíči.
     *  - [DUAL]    - přechod: posloucháme legacy i ratchet, odesíláme legacy,
     *                dokud se nepotvrdí, že protějšek ratchet přijímá.
     *  - [RATCHET] - plně na ratchetu.
     */
    enum class Mode { LEGACY, DUAL, RATCHET }

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
                mode = mode
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
    val marker: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("e", epoch)
        .put("n", msgNo)
        .put("mk", messageKeyB64)
        .put("m", marker)

    companion object {
        fun fromJson(o: JSONObject): SkippedMessageKey? {
            val mk = o.optStringOrNull("mk") ?: return null
            return SkippedMessageKey(
                epoch = o.optInt("e", 0),
                msgNo = o.optInt("n", 0),
                messageKeyB64 = mk,
                marker = o.optLong("m", 0)
            )
        }
    }
}

/** `optString`, který pro chybějící / prázdné / `null` pole vrátí `null` (ne ""). */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
