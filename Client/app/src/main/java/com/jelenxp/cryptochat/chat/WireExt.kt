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
 * | typ | jméno     | od minoru | význam                                     |
 * |-----|-----------|-----------|--------------------------------------------|
 * | 1   | MSG_ID    | 2         | stabilní ID zprávy napříč zařízeními        |
 * | 2   | REPLY_TO  | 2         | odpověď na zprávu (v1.2)                    |
 * | 3   | CONTROL   | 2         | řídicí zpráva, viz [Control]                |
 * | 4   | REACTION  | 2         | reakce emoji (v1.2)                         |
 * | 5   | MAX_MAJOR | 4         | nejvyšší wire MAJOR, který odesílatel UMÍ    |
 * |     |           |           | PŘEČÍST - pojistka pro budoucí major migraci |
 * | 6   | CAPS      | -         | bitmapa schopností (feature flags), aby nové |
 * |     |           |           | funkce nemusely zvyšovat WIRE_MINOR          |
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
    const val TYPE_MAX_MAJOR = 5
    const val TYPE_CAPABILITIES = 6

    /** Délka stabilního ID zprávy. 16 B = stejná odolnost proti kolizi jako UUID. */
    const val MSG_ID_BYTES = 16

    // --- Schopnosti (capability bitmap) ---
    //
    // Feature flags inzerované v KAŽDÉ zprávě. Nová funkce si vezme volný bit,
    // inzeruje ho a odesílatel se pak rozhoduje podle
    // [WireCompat.peerHasCapability], NE podle [WireCompat.WIRE_MINOR]. Díky tomu
    // nové funkce NEMUSÍ zvyšovat minor - kompatibilitu už řeší přeskočení
    // neznámého TLV (starší verze bit prostě nezná a schopnost nenabídne).
    //
    // Přítomnost tohoto TLV zároveň znamená „umím schopnosti" (i prázdná
    // bitmapa) - odlišuje protějšek, který kanál zná, od staršího, co ho neposílá.
    //
    // Registr bitů - čísla se NIKDY nerecyklují (ani když funkce zanikne):
    // | bit | jméno     | význam                    |
    // |-----|-----------|---------------------------|
    // | 0   | REACTIONS | umí zobrazit reakce emoji |
    const val CAP_REACTIONS = 0

    /** Schopnosti, které TAHLE verze umí a inzeruje protějšku. */
    val LOCAL_CAPABILITIES: Set<Int> = setOf(CAP_REACTIONS)

    /**
     * Zabalí množinu bitů do bitmapy (LSB-first): bit `i` leží v bajtu `i/8`,
     * na pozici `i%8`. Prázdná množina → prázdné pole (= protějšek zná kanál,
     * ale žádnou volitelnou schopnost nemá).
     */
    internal fun encodeCapabilities(caps: Set<Int>): ByteArray {
        val valid = caps.filter { it >= 0 }
        if (valid.isEmpty()) return ByteArray(0)
        val out = ByteArray(valid.max() / 8 + 1)
        for (b in valid) out[b / 8] = (out[b / 8].toInt() or (1 shl (b % 8))).toByte()
        return out
    }

    /** Opak [encodeCapabilities]. Neznámé (vyšší) bity se prostě přečtou taky. */
    internal fun decodeCapabilities(bytes: ByteArray): Set<Int> {
        val out = HashSet<Int>()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            if (v == 0) continue
            for (bit in 0..7) if ((v ushr bit) and 1 == 1) out.add(i * 8 + bit)
        }
        return out
    }

    // --- Stropy. Trailer plní protějšek, takže tady se nevěří ničemu. ---

    /** Celý trailer. Nad tím je to buď chyba, nebo pokus o zahlcení. */
    const val MAX_TRAILER_BYTES = 4096

    /** Kolik TLV nejvýš. Bez tohohle by protějšek poslal statisíce prázdných. */
    const val MAX_TLV_COUNT = 32

    /** Nejdelší jedna hodnota. */
    const val MAX_TLV_VALUE_BYTES = 1024

    /**
     * Reakce na zprávu (emoji). Řídicí funkce zavedená ve wire minoru 3.
     *
     * Registr `feature id` - čísla se stejně jako typy TLV NIKDY nerecyklují:
     * | id | jméno    | od minoru |
     * |----|----------|-----------|
     * | 1  | REACTION | 3         |
     */
    const val FEATURE_REACTION = 1

    /** Funkce, které TAHLE verze umí zpracovat jako řídicí zprávu. */
    private val KNOWN_FEATURES = setOf(FEATURE_REACTION)

    /** Umí tahle verze danou řídicí funkci? */
    fun isKnownFeature(featureId: Int): Boolean = featureId in KNOWN_FEATURES

    // --- Reakce ---

    /** Nejdelší emoji v bajtech. Sedm rodinných emoji se ZWJ se vejde. */
    const val MAX_EMOJI_BYTES = 64

    /** Nejvíc codepointů. Drží délku i u sekvencí se ZWJ (👨‍👩‍👧‍👦 = 7). */
    const val MAX_EMOJI_CODEPOINTS = 12

    /** Reakce vytažená z traileru. */
    data class ReactionData(val targetHex: String, val emoji: String, val remove: Boolean)

    /** Operace reakce. Čísla se stejně jako typy TLV nerecyklují. */
    const val OP_SET = 0
    const val OP_REMOVE = 1

    /**
     * Je řetězec přijatelný jako reakce?
     *
     * Hodnotu volí protějšek, takže se omezuje délka a zakazují znaky, kterými
     * jde ošidit vykreslení - hlavně **obousměrné přepínače** (U+202A-202E,
     * U+2066-2069, LRM/RLM), kterými se dá text zobrazit pozpátku, a řídicí
     * znaky. ZWJ (U+200D) a variantní selektor (U+FE0F) se naopak povolují -
     * bez nich by složená emoji jako 👨‍👩‍👧 nefungovala.
     *
     * Schválně se NEkontroluje, jestli jde opravdu o emoji: seznam by zastaral
     * s každou verzí Unicode a nové emoji by přestaly chodit. Proti zneužití
     * jako „textová zpráva" stačí strop délky a pevná velikost místa v UI.
     */
    fun isValidEmoji(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.toByteArray(Charsets.UTF_8).size > MAX_EMOJI_BYTES) return false
        var i = 0
        var count = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isForbidden(cp)) return false
            i += Character.charCount(cp)
            count++
            if (count > MAX_EMOJI_CODEPOINTS) return false
        }
        return true
    }

    /**
     * Odstraní z textu obousměrné přepínače a řídicí znaky (stejná sada jako
     * [isForbidden] u emoji). Řetězec plně řídí protějšek - u názvu souboru /
     * MIME z manifestu by RLO (U+202E) mohl zamaskovat příponu (`faktura‮gnp.js`
     * → vypadá jako `.png`) nebo řídicími znaky rozbít vykreslení. Na disk se
     * ukládá samostatně očištěný název; tohle je pro BEZPEČNÉ ZOBRAZENÍ.
     */
    fun sanitizeForDisplay(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isForbidden(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isForbidden(cp: Int): Boolean =
        cp < 0x20 ||                    // C0 (řídicí znaky, konce řádků)
        cp == 0x7F ||                   // DEL
        cp in 0x80..0x9F ||             // C1
        cp == 0x00AD ||                 // měkký spojovník (neviditelný)
        cp == 0x061C ||                 // arabská značka (taky obousměrná)
        cp == 0x200E || cp == 0x200F || // LRM / RLM
        cp in 0x202A..0x202E ||         // obousměrné vkládání a přepnutí
        cp == 0x2028 || cp == 0x2029 || // oddělovač řádku / odstavce
        cp in 0x2066..0x2069 ||         // obousměrné izoláty
        cp in 0xFFF9..0xFFFB            // meziřádkové anotace

    /**
     * Hodnota TLV reakce: `[16B cíl][1B operace: 0=nastav, 1=zruš][2B délka][emoji UTF-8]`.
     */
    fun buildReaction(target: ByteArray, emoji: String, remove: Boolean): ByteArray {
        require(target.size == MSG_ID_BYTES) { "Cíl reakce musí mít $MSG_ID_BYTES B" }
        require(remove || isValidEmoji(emoji)) { "Neplatné emoji" }
        val e = if (remove) ByteArray(0) else emoji.toByteArray(Charsets.UTF_8)
        val out = ByteArray(MSG_ID_BYTES + 1 + 2 + e.size)
        System.arraycopy(target, 0, out, 0, MSG_ID_BYTES)
        out[MSG_ID_BYTES] = (if (remove) OP_REMOVE else OP_SET).toByte()
        out[MSG_ID_BYTES + 1] = ((e.size ushr 8) and 0xFF).toByte()
        out[MSG_ID_BYTES + 2] = (e.size and 0xFF).toByte()
        System.arraycopy(e, 0, out, MSG_ID_BYTES + 3, e.size)
        return out
    }

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

        /** ID zprávy, na kterou se odpovídá, nebo null. */
        val replyToHex: String?
            get() = first(TYPE_REPLY_TO)?.takeIf { it.size == MSG_ID_BYTES }?.let { toHex(it) }

        /** Nejvyšší wire MAJOR, který odesílatel umí přečíst, nebo null. */
        val maxMajor: Int?
            get() = first(TYPE_MAX_MAJOR)?.takeIf { it.size == 1 }?.let { it[0].toInt() and 0xFF }

        /**
         * Schopnosti (feature flags), které odesílatel inzeruje, nebo null když
         * trailer bitmapu nenese (starší verze bez capability kanálu). Prázdná
         * množina znamená „kanál zná, ale žádnou volitelnou schopnost nemá".
         */
        val capabilities: Set<Int>?
            get() = first(TYPE_CAPABILITIES)?.let { decodeCapabilities(it) }

        /**
         * Reakce, nebo null když ji trailer nenese nebo je vadná. Všechno v ní
         * pochází od protějšku, takže se ověřuje délka, mez i samotné emoji.
         */
        val reaction: ReactionData?
            get() {
                val v = first(TYPE_REACTION) ?: return null
                if (v.size < MSG_ID_BYTES + 3) return null
                val target = toHex(v.copyOfRange(0, MSG_ID_BYTES))
                // Jen známé operace. Kdyby budoucí verze zavedla další (třeba
                // „nahradit"), nesmí ji tahle pochopit jako zrušení - radši
                // reakci zahodit, tělo je stejně prázdné.
                val op = v[MSG_ID_BYTES].toInt() and 0xFF
                if (op != OP_SET && op != OP_REMOVE) return null
                val remove = op == OP_REMOVE
                val len = ((v[MSG_ID_BYTES + 1].toInt() and 0xFF) shl 8) or
                    (v[MSG_ID_BYTES + 2].toInt() and 0xFF)
                // Odečítáme, ať délka nemůže přetéct přes konec hodnoty.
                if (len > v.size - MSG_ID_BYTES - 3) return null
                val emoji = String(
                    v.copyOfRange(MSG_ID_BYTES + 3, MSG_ID_BYTES + 3 + len),
                    Charsets.UTF_8
                )
                if (remove) return ReactionData(target, "", true)
                if (!isValidEmoji(emoji)) return null
                return ReactionData(target, emoji, false)
            }

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

        /**
         * Odkaz na zprávu, na kterou se odpovídá. Délka se hlídá tady: čtecí
         * strana nesedící délku tiše zahodí, takže bez téhle kontroly by
         * odpověď odešla bez odkazu a nikdo by se to nedozvěděl.
         */
        fun putReplyTo(replyTo: ByteArray): Builder {
            require(replyTo.size == MSG_ID_BYTES) { "REPLY_TO musí mít $MSG_ID_BYTES B" }
            return put(TYPE_REPLY_TO, replyTo)
        }

        /**
         * Inzerce „nejvyšší wire MAJOR, který umím PŘEČÍST". Posílá se v každé
         * zprávě, aby při budoucí major migraci odesílatel poznal, kdy smí
         * protějšku bezpečně přepnout obálku na nový major (viz [WireCompat]).
         */
        fun putMaxMajor(major: Int): Builder {
            require(major in 1..255) { "MAX_MAJOR mimo rozsah: $major" }
            return put(TYPE_MAX_MAJOR, byteArrayOf(major.toByte()))
        }

        /**
         * Inzerce bitmapy schopností. Posílá se v KAŽDÉ zprávě (i pro prázdnou
         * množinu - přítomnost TLV sama značí „umím schopnosti"). Protějšek si ji
         * zaznamená a nové funkce se pak gatují přes
         * [WireCompat.peerHasCapability], takže NEMUSÍ zvyšovat wire minor.
         */
        fun putCapabilities(caps: Set<Int>): Builder =
            put(TYPE_CAPABILITIES, encodeCapabilities(caps))

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
