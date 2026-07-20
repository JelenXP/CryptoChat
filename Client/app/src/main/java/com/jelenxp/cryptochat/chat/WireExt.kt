package com.jelenxp.cryptochat.chat

import java.security.SecureRandom

/**
 * Rozšiřující data zprávy („trailer") - obecný mechanismus, jak do obálky
 * přidávat nové věci tak, aby je STARŠÍ verze appky bezpečně přehlédla.
 *
 * ## Proč zrovna takhle
 *
 * [ChatEnvelope.open] čte z payloadu přesně `len` bajtů dat a o zbytek se
 * nezajímá (u textu tam dosud ležela jen nulová výplň). Cokoli zapsaného ZA
 * datovou oblast je tedy pro každý starší parser neviditelné - a protože to
 * leží uvnitř GCM šifry, je to zároveň autentizované: relay do toho nevidí
 * ani to nemůže přepsat.
 *
 * Tohle je jediný způsob, jak do formátu přidat něco nového bez zvýšení
 * [WireCompat.WIRE_MAJOR]. Nový `kind` tuhle vlastnost NEMÁ - starší appka ho
 * neumí zařadit a blob skončí v [BlobQuarantine] (viz pravidla ve [WireCompat]).
 *
 * ## Formát
 *
 * ```
 * [2B magie 0xCC 0x2A][2B délka TLV bloku BE][TLV…]
 * TLV: [1B typ][2B délka BE][hodnota]
 * ```
 *
 * Magie je tam proto, že výplň textu jsou nuly - nulový bajt tedy nikdy
 * nevypadá jako začátek traileru a starou zprávu bez traileru nejde splést
 * s novou.
 *
 * ## Pravidla, která platí navždy
 *
 *  1. **Neznámý typ TLV se PŘESKAKUJE**, nikdy není fatální. Díky tomu smí
 *     novější verze posílat věci, o kterých tahle ještě neví.
 *  2. **Poškozený trailer znamená „trailer tam není"**. Zpráva se pořád
 *     zobrazí - ztratit ji kvůli vadné ozdobě by bylo mnohem horší než
 *     zahodit ozdobu.
 *  3. **Čísla typů se nikdy nerecyklují** (registr níže), i když funkce
 *     zanikne.
 *  4. Všechno má strop. Obsah traileru řídí protějšek, ne my.
 *
 * ## Registr typů (NIKDY nepoužívat číslo znovu)
 *
 * | typ | jméno    | od minoru | význam                                     |
 * |-----|----------|-----------|--------------------------------------------|
 * | 1   | MSG_ID   | 2         | stabilní ID zprávy napříč zařízeními        |
 * | 2   | REPLY_TO | 2         | REZERVOVÁNO (odpovědi, v1.2)                |
 * | 3   | CONTROL  | 2         | řídicí zpráva, viz [Control]                |
 * | 4   | REACTION | 2         | REZERVOVÁNO (reakce, v1.2)                  |
 */
object WireExt {

    private const val MAGIC_0 = 0xCC.toByte()
    private const val MAGIC_1 = 0x2A.toByte()

    /** Magie (2 B) + délka bloku (2 B). */
    private const val PREFIX = 4

    /** Hlavička jednoho TLV: typ (1 B) + délka (2 B). */
    private const val TLV_HEADER = 3

    // --- Registr typů (viz tabulka v dokumentaci třídy) ---
    const val TYPE_MSG_ID = 1
    const val TYPE_REPLY_TO = 2
    const val TYPE_CONTROL = 3
    const val TYPE_REACTION = 4

    /** Délka stabilního ID zprávy. 16 B = stejná odolnost proti kolizi jako UUID. */
    const val MSG_ID_BYTES = 16

    // --- Stropy. Trailer plní protějšek, takže tady se nevěří ničemu. ---

    /** Celý trailer. Nad tím je to buď chyba, nebo pokus o zahlcení. */
    const val MAX_TRAILER_BYTES = 4096

    /** Kolik TLV nejvýš. Bez tohohle by protějšek poslal statisíce prázdných. */
    const val MAX_TLV_COUNT = 32

    /** Nejdelší jedna hodnota. */
    const val MAX_TLV_VALUE_BYTES = 1024

    /**
     * Funkce, které TAHLE verze umí zpracovat jako řídicí zprávu. Prázdné:
     * v1.1 zavádí jen mechanismus, žádnou řídicí funkci zatím neposílá.
     * Reakce (v1.2) sem přidají svoje id.
     */
    private val KNOWN_FEATURES = emptySet<Int>()

    /** Umí tahle verze danou řídicí funkci? */
    fun isKnownFeature(featureId: Int): Boolean = featureId in KNOWN_FEATURES

    /**
     * Řídicí zpráva: nenese obsah pro uživatele, ale pokyn (reakce, potvrzení
     * o přečtení…). Hodnota TLV: `[2B id funkce BE][1B příznaky]`.
     *
     * ## Kontrakt, na kterém závisí bezpečnost starších verzí
     *
     * **Řídicí zpráva MUSÍ mít prázdnou datovou oblast** (`len == 0`). Vlastní
     * data pokynu patří do dalších TLV, ne do těla zprávy.
     *
     * Proč tak přísně: příjemce, který funkci nezná, musí umět rozhodnout
     * „tohle můžu zahodit". Kdyby řídicí příznak směl viset na zprávě
     * s obsahem, starší verze by ho zahodila i s tím obsahem - a protože relay
     * zprávu po potvrzení maže, byla by **nenávratně pryč**. Proto se řídicí
     * zpráva pozná podle prázdného těla a zpráva s obsahem se nikdy nezahazuje
     * (viz [ChatEnvelope.open]).
     *
     * Chceš-li, aby starší verze místo ticha ukázala náhradní větu (strategie
     * FALLBACK), prostě ji dej do těla zprávy - tělo s obsahem se zobrazí vždy.
     *
     * [flags] je zatím rezervované pro budoucí použití.
     */
    data class Control(val featureId: Int, val flags: Int)

    /** Rozparsovaný trailer. Prázdný seznam = platný trailer bez položek. */
    class Trailer internal constructor(private val entries: List<Pair<Int, ByteArray>>) {

        /** První hodnota daného typu, nebo null. Duplicity se ignorují. */
        fun first(type: Int): ByteArray? = entries.firstOrNull { it.first == type }?.second

        /** Typy, které trailer obsahuje (i neznámé). */
        val types: Set<Int> get() = entries.map { it.first }.toSet()

        /** Stabilní ID zprávy jako hex, nebo null když ho trailer nenese. */
        val msgIdHex: String?
            get() = first(TYPE_MSG_ID)?.takeIf { it.size == MSG_ID_BYTES }?.let { toHex(it) }

        /** Řídicí pokyn, nebo null když zpráva žádný nenese. */
        val control: Control?
            get() {
                val v = first(TYPE_CONTROL) ?: return null
                if (v.size < 3) return null
                val featureId = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
                return Control(featureId, v[2].toInt() and 0xFF)
            }
    }

    /** Skládá trailer k odeslání. */
    class Builder {
        private val entries = ArrayList<Pair<Int, ByteArray>>()

        fun put(type: Int, value: ByteArray): Builder {
            // Tohle plníme MY, takže porušení stropu je chyba v kódu - ať spadne
            // v testu, ne až na drátě.
            require(type in 1..255) { "Typ TLV mimo rozsah: $type" }
            require(value.size <= MAX_TLV_VALUE_BYTES) { "Hodnota TLV je přes limit" }
            entries.add(type to value)
            return this
        }

        fun putMsgId(msgId: ByteArray): Builder {
            require(msgId.size == MSG_ID_BYTES) { "MSG_ID musí mít $MSG_ID_BYTES B" }
            return put(TYPE_MSG_ID, msgId)
        }

        /** Prázdné pole, když není co posílat - pak se trailer vůbec nezapíše. */
        fun build(): ByteArray {
            if (entries.isEmpty()) return ByteArray(0)
            require(entries.size <= MAX_TLV_COUNT) { "Příliš mnoho TLV" }
            val body = entries.sumOf { TLV_HEADER + it.second.size }
            require(body <= MAX_TRAILER_BYTES) { "Trailer je přes limit" }
            val out = ByteArray(PREFIX + body)
            out[0] = MAGIC_0
            out[1] = MAGIC_1
            out[2] = ((body ushr 8) and 0xFF).toByte()
            out[3] = (body and 0xFF).toByte()
            var p = PREFIX
            for ((type, value) in entries) {
                out[p++] = (type and 0xFF).toByte()
                out[p++] = ((value.size ushr 8) and 0xFF).toByte()
                out[p++] = (value.size and 0xFF).toByte()
                System.arraycopy(value, 0, out, p, value.size)
                p += value.size
            }
            return out
        }
    }

    /**
     * Přečte trailer z [payload] od pozice [offset] (= konec datové oblasti).
     *
     * Vrací null, když tam trailer není NEBO je poškozený - volající se v obou
     * případech chová stejně (zpráva bez ozdob). Tahle funkce nesmí nikdy
     * vyhodit výjimku: běží nad daty od protějšku a pád by stál zprávu.
     */
    fun parse(payload: ByteArray, offset: Int): Trailer? {
        return try {
            if (offset < 0 || offset > payload.size - PREFIX) return null
            if (payload[offset] != MAGIC_0 || payload[offset + 1] != MAGIC_1) return null
            val body = ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                (payload[offset + 3].toInt() and 0xFF)
            if (body > MAX_TRAILER_BYTES) return null
            // Odečítáme, ne přičítáme - offset + PREFIX + body by u velkých čísel
            // přeteklo a kontrola by prolezla.
            if (body > payload.size - offset - PREFIX) return null
            val end = offset + PREFIX + body
            var p = offset + PREFIX
            val entries = ArrayList<Pair<Int, ByteArray>>()
            while (p < end) {
                if (p > end - TLV_HEADER) return null       // useknutá hlavička TLV
                val type = payload[p].toInt() and 0xFF
                val len = ((payload[p + 1].toInt() and 0xFF) shl 8) or
                    (payload[p + 2].toInt() and 0xFF)
                p += TLV_HEADER
                if (len > MAX_TLV_VALUE_BYTES) return null
                if (len > end - p) return null              // hodnota přesahuje blok
                if (entries.size >= MAX_TLV_COUNT) return null
                entries.add(type to payload.copyOfRange(p, p + len))
                p += len
            }
            Trailer(entries)
        } catch (e: Exception) {
            // Pojistka: ani neočekávaná výjimka nesmí stát zprávu.
            null
        }
    }

    /** Nové náhodné ID zprávy. */
    fun randomMsgId(): ByteArray = ByteArray(MSG_ID_BYTES).also { SecureRandom().nextBytes(it) }

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Hex bez oddělovačů (malá písmena).
     *
     * Schválně přes tabulku, ne `String.format` - ten se řídí výchozím locale
     * zařízení a nechceme ani teoretickou možnost, že by ID zprávy vzniklo
     * s jinými než ASCII číslicemi. Rozbilo by to [fromHex] i porovnávání ID.
     */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Opak [toHex]. Vrací null, když řetězec není platný hex očekávané délky. */
    fun fromHex(hex: String, expectedBytes: Int = MSG_ID_BYTES): ByteArray? {
        if (hex.length != expectedBytes * 2) return null
        val out = ByteArray(expectedBytes)
        for (i in 0 until expectedBytes) {
            val v = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = v.toByte()
        }
        return out
    }
}
