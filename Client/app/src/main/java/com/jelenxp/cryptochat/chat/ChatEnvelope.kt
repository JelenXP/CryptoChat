package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.CryptoManager
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Obálka jedné chatové zprávy pro přenos přes relay. Obsah se zašifruje
 * AES-256-GCM sdíleným klíčem kontaktu (stejným, jaký používá [CryptoManager]),
 * takže relay ani nikdo po cestě nevidí obsah.
 *
 * Podporuje dva druhy (první bajt uvnitř šifry = KIND):
 *   - TEXT: doplněný (padding) na fixní „koš", aby ani délka neprozradila,
 *     jak dlouhá zpráva byla.
 *   - IMAGE: syrové bajty JPEG (bez paddingu - obrázek se posílá jako jeden blob,
 *     odesílatel ho komprimuje pod limit relaye).
 *
 * Formát otevřeného obsahu (uvnitř šifry, chráněný GCM tagem):
 *   [1B kind][1B minor][8B timestamp BE][4B délka dat BE][data][trailer?][(u textu) výplň nulami]
 *
 * Za datovou oblastí smí ležet **trailer** ([WireExt]) - rozšiřující data, která
 * starší verze appky nevidí, protože čte přesně `len` bajtů. Právě tudy se do
 * formátu přidávají novinky bez zvýšení [WireCompat.WIRE_MAJOR].
 *
 * Výstupní blob: `IV[12] || ciphertext || GCM tag[16]`.
 *
 * **Směr je součástí autentizace (AAD).** Obě schránky kontaktu (dir 0 a 1) se
 * odvozují ze stejného klíče, takže bez tohohle svázání by relay mohl vzít blob
 * z odchozí schránky a položit ho do příchozí - uživateli by se jeho vlastní
 * zpráva zobrazila jako přijatá od protějšku. Se směrem v AAD takový blob
 * neprojde GCM kontrolou a jen se zahodí.
 */
object ChatEnvelope {

    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    // 1B kind + 1B minor verze + 8B timestamp + 4B délka.
    // Minor je tady (uvnitř šifry) schválně: číst ho jde jen když sedí major,
    // a tehdy dešifrování funguje. Relayi tak nepřibude žádná metadata.
    private const val HEADER = 14

    private const val KIND_TEXT: Byte = 0
    private const val KIND_IMAGE: Byte = 1
    private const val KIND_FILE_MANIFEST: Byte = 2
    private const val KIND_FILE_CHUNK: Byte = 3

    /** Délka identifikátoru přenášeného souboru (spojuje manifest s kousky). */
    const val FILE_ID_BYTES = 16

    // Koše pro padding textu (bajty).
    private val BUCKETS = intArrayOf(256, 1024, 4096, 16_384, 65_536, 262_144)

    /** Výsledek dešifrování - text, fotka, nebo manifest/kousek většího souboru. */
    sealed interface Opened {
        val timestamp: Long
        data class Text(override val timestamp: Long, val text: String) : Opened
        data class Image(override val timestamp: Long, val bytes: ByteArray) : Opened

        /** Ohlášení souboru: co přijde a na kolik kousků je rozdělený. */
        data class FileManifest(
            override val timestamp: Long,
            val fileId: ByteArray,
            val totalChunks: Int,
            val totalSize: Long,
            val mimeType: String,
            val fileName: String
        ) : Opened

        /** Jeden kousek souboru. */
        data class FileChunk(
            override val timestamp: Long,
            val fileId: ByteArray,
            val index: Int,
            val bytes: ByteArray
        ) : Opened

        /**
         * Reakce na zprávu. Není to zpráva pro historii - jen se přilepí
         * k cílové zprávě, nezvyšuje nepřečtené a neposílá notifikaci.
         */
        data class Reaction(
            override val timestamp: Long,
            val targetHex: String,
            val emoji: String,
            val remove: Boolean
        ) : Opened
    }

    /**
     * Zabalí a zašifruje textovou zprávu (s paddingem přes koše).
     *
     * [msgId] je stabilní ID zprávy napříč zařízeními - veze se v traileru,
     * takže starší appka ho přehlédne a zprávu zobrazí jako obyčejný text.
     */
    fun seal(
        text: String,
        timestamp: Long,
        keyBase64: String,
        dir: Int,
        msgId: ByteArray? = null,
        replyTo: ByteArray? = null
    ): ByteArray {
        return encrypt(buildTextPayload(text, timestamp, msgId, replyTo), keyBase64, dir)
    }

    /**
     * Vnitřní plaintext textové zprávy (bez šifrování) - sdílené legacy i ratchet
     * obálkou. Ratchet mění jen vnější obálku a klíč, vnitřek je stejný.
     */
    internal fun buildTextPayload(
        text: String,
        timestamp: Long,
        msgId: ByteArray?,
        replyTo: ByteArray?
    ): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        val trailer = trailerFor(msgId, replyTo)
        // Do koše se počítá i trailer - jinak by výplň skončila dřív, než trailer
        // začíná, a zpráva by se usekla.
        val padded = ByteArray(bucketFor(HEADER + data.size + trailer.size))
        writeHeader(padded, KIND_TEXT, timestamp, data.size)
        System.arraycopy(data, 0, padded, HEADER, data.size)
        System.arraycopy(trailer, 0, padded, HEADER + data.size, trailer.size)
        return padded
    }

    /** Zabalí a zašifruje fotku (JPEG bajty, bez paddingu). */
    fun sealImage(
        jpeg: ByteArray,
        timestamp: Long,
        keyBase64: String,
        dir: Int,
        msgId: ByteArray? = null
    ): ByteArray {
        return encrypt(buildImagePayload(jpeg, timestamp, msgId), keyBase64, dir)
    }

    /** Vnitřní plaintext fotky (bez šifrování). */
    internal fun buildImagePayload(jpeg: ByteArray, timestamp: Long, msgId: ByteArray?): ByteArray {
        val trailer = trailerFor(msgId)
        val payload = ByteArray(HEADER + jpeg.size + trailer.size)
        writeHeader(payload, KIND_IMAGE, timestamp, jpeg.size)
        System.arraycopy(jpeg, 0, payload, HEADER, jpeg.size)
        System.arraycopy(trailer, 0, payload, HEADER + jpeg.size, trailer.size)
        return payload
    }

    /**
     * Trailer s ID zprávy, odkazem na odpověď a VŽDY s inzercí [maxMajor]
     * ([WireCompat.MAX_READABLE_MAJOR]) a bitmapy schopností
     * ([WireExt.LOCAL_CAPABILITIES]) - obojí jede v každé zprávě, aby protějšek
     * poznal, co odesílatel umí (major migrace i gate nových funkcí), viz
     * [WireCompat].
     */
    private fun trailerFor(msgId: ByteArray?, replyTo: ByteArray? = null): ByteArray {
        val b = WireExt.Builder()
        msgId?.let { b.putMsgId(it) }
        replyTo?.let { b.putReplyTo(it) }
        b.putMaxMajor(WireCompat.MAX_READABLE_MAJOR)
        b.putCapabilities(WireExt.LOCAL_CAPABILITIES)
        return b.build()
    }

    /**
     * Reakce na zprávu jako **řídicí zpráva**: tělo je prázdné a všechno je
     * v traileru. Prázdné tělo je součást kontraktu (viz [WireExt.Control]) -
     * díky němu ji verze, která reakce neumí, bezpečně zahodí, místo aby
     * uživateli ukázala prázdnou bublinu.
     *
     * [remove] = zrušení reakce; pak se [emoji] ignoruje.
     */
    fun sealReaction(
        target: ByteArray,
        emoji: String,
        remove: Boolean,
        timestamp: Long,
        keyBase64: String,
        dir: Int
    ): ByteArray {
        return encrypt(buildReactionPayload(target, emoji, remove, timestamp), keyBase64, dir)
    }

    /** Vnitřní plaintext reakce (řídicí zpráva: prázdné tělo, vše v traileru). */
    internal fun buildReactionPayload(
        target: ByteArray,
        emoji: String,
        remove: Boolean,
        timestamp: Long
    ): ByteArray {
        val control = byteArrayOf(
            ((WireExt.FEATURE_REACTION ushr 8) and 0xFF).toByte(),
            (WireExt.FEATURE_REACTION and 0xFF).toByte(),
            0 // příznaky, zatím žádné
        )
        val trailer = WireExt.Builder()
            .put(WireExt.TYPE_CONTROL, control)
            .put(WireExt.TYPE_REACTION, WireExt.buildReaction(target, emoji, remove))
            .putMaxMajor(WireCompat.MAX_READABLE_MAJOR)
            .putCapabilities(WireExt.LOCAL_CAPABILITIES)
            .build()
        // Do koše jako text - reakce tak na drátě vypadá jako krátká zpráva
        // a relay z velikosti nepozná, že jde o reakci.
        val padded = ByteArray(bucketFor(HEADER + trailer.size))
        writeHeader(padded, KIND_TEXT, timestamp, 0)
        System.arraycopy(trailer, 0, padded, HEADER, trailer.size)
        return padded
    }

    /**
     * Ohlášení souboru před posláním kousků.
     * Data: `[16B fileId][4B počet kousků][8B celková velikost][2B délka mime][mime][2B délka názvu][název]`
     */
    fun sealFileManifest(
        fileId: ByteArray,
        totalChunks: Int,
        totalSize: Long,
        mimeType: String,
        fileName: String,
        timestamp: Long,
        keyBase64: String,
        dir: Int
    ): ByteArray {
        return encrypt(
            buildManifestPayload(fileId, totalChunks, totalSize, mimeType, fileName, timestamp),
            keyBase64, dir
        )
    }

    /** Vnitřní plaintext manifestu souboru (bez šifrování). */
    internal fun buildManifestPayload(
        fileId: ByteArray,
        totalChunks: Int,
        totalSize: Long,
        mimeType: String,
        fileName: String,
        timestamp: Long
    ): ByteArray {
        val mime = mimeType.toByteArray(Charsets.UTF_8)
        val name = fileName.toByteArray(Charsets.UTF_8)
        val data = ByteBuffer.allocate(FILE_ID_BYTES + 4 + 8 + 2 + mime.size + 2 + name.size)
        data.put(fileId).putInt(totalChunks).putLong(totalSize)
        data.putShort(mime.size.toShort()).put(mime)
        data.putShort(name.size.toShort()).put(name)
        return buildRawPayload(KIND_FILE_MANIFEST, data.array(), timestamp)
    }

    /** Jeden kousek souboru. Data: `[16B fileId][4B index][bajty]`. */
    fun sealFileChunk(
        fileId: ByteArray,
        index: Int,
        chunk: ByteArray,
        timestamp: Long,
        keyBase64: String,
        dir: Int
    ): ByteArray {
        return encrypt(buildChunkPayload(fileId, index, chunk, timestamp), keyBase64, dir)
    }

    /** Vnitřní plaintext kousku souboru (bez šifrování). */
    internal fun buildChunkPayload(
        fileId: ByteArray,
        index: Int,
        chunk: ByteArray,
        timestamp: Long
    ): ByteArray {
        val data = ByteBuffer.allocate(FILE_ID_BYTES + 4 + chunk.size)
        data.put(fileId).putInt(index).put(chunk)
        return buildRawPayload(KIND_FILE_CHUNK, data.array(), timestamp)
    }

    /** Vnitřní plaintext obecné zprávy typu [kind] s daty (bez šifrování). */
    private fun buildRawPayload(kind: Byte, data: ByteArray, timestamp: Long): ByteArray {
        val payload = ByteArray(HEADER + data.size)
        writeHeader(payload, kind, timestamp, data.size)
        System.arraycopy(data, 0, payload, HEADER, data.size)
        return payload
    }

    /**
     * Výsledek otevření blobu. **Rozlišit tyhle tři stavy je zásadní** - dřív
     * se všechny slévaly do `null` a volající je nemohl odlišit, takže i zpráva,
     * které nikdy nebudeme rozumět, se 30 dní opakovaně zkoušela z karantény.
     */
    sealed interface Result {

        /**
         * Rozbaleno. [msgIdHex] je stabilní ID zprávy, když ho odesílatel
         * poslal; [replyToHex] ID zprávy, na kterou se odpovídá.
         */
        data class Ok(
            val senderMinor: Int,
            val content: Opened,
            val msgIdHex: String? = null,
            val replyToHex: String? = null,
            /** Nejvyšší wire major, který odesílatel umí přečíst (z inzerce). */
            val maxMajor: Int? = null,
            /** Schopnosti (feature flags) inzerované odesílatelem, nebo null. */
            val capabilities: Set<Int>? = null
        ) : Result

        /**
         * **Řídicí zpráva bez obsahu** pro funkci, kterou tahle verze neumí
         * (reakce, potvrzení o přečtení z novější appky).
         *
         * Volající ji má **zahodit a potvrdit**. Nic se tím neztratí: řídicí
         * zpráva má z definice prázdnou datovou oblast (viz [WireExt.Control]),
         * takže tu není žádný obsah pro uživatele. Odkládat ji do karantény by
         * znamenalo 30 dní zbytečných pokusů o něco, co je stejně jen ozdoba.
         */
        data class Unsupported(val senderMinor: Int) : Result

        /**
         * **Nejde přečíst TEĎ.** Cizí klíč, poškození, jiné rozložení hlavičky,
         * nebo `kind`, kterému tahle verze nerozumí.
         *
         * Volající ji má **odložit do karantény** a zkusit znovu. Klíčové je,
         * že sem patří i neznámý `kind`: může to být plnohodnotná zpráva, kterou
         * by novější verze appky přečíst uměla. Zahodit ji natrvalo by znamenalo
         * nevratnou ztrátu - relay ji po potvrzení maže.
         */
        data object Unreadable : Result
    }

    /**
     * Dešifruje a rozbalí blob. Viz [Result] - vrací tři různé stavy, ne jen
     * „povedlo/nepovedlo".
     */
    fun open(blob: ByteArray, keyBase64: String, dir: Int): Result {
        val payload = try {
            // [1B major verze][12B IV][ciphertext+tag]
            if (blob.size <= 1 + IV_SIZE_BYTES) return Result.Unreadable
            val major = blob[0].toInt() and 0xFF
            if (major != WireCompat.WIRE_MAJOR) return Result.Unreadable
            val iv = blob.copyOfRange(1, 1 + IV_SIZE_BYTES)
            val cipherBytes = blob.copyOfRange(1 + IV_SIZE_BYTES, blob.size)
            val key = CryptoManager.keyFromBase64(keyBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            // AAD se skládá z NAŠÍ konstanty, ne z bajtu přečteného z blobu -
            // aby ho nikdy nemohl řídit útočník (výše sice musí sedět, ale
            // spoléhat se na to je zbytečně křehké).
            cipher.updateAAD(aad(dir, WireCompat.WIRE_MAJOR))
            cipher.doFinal(cipherBytes)
        } catch (e: Exception) {
            return Result.Unreadable
        }
        return try {
            parsePayload(payload)
        } catch (e: Exception) {
            // Rozbalení po úspěšném dešifrování by padat nemělo. Když přesto
            // spadne, ber to jako neshodu formátu (karanténa), ne jako něco,
            // čemu nikdy neporozumíme - zpráva tak dostane další šanci.
            Result.Unreadable
        }
    }

    /**
     * Rozbalí už dešifrovaný payload. Oddělené od dešifrování schválně: díky
     * tomu jde odlišit „špatný klíč" od „rozumím šifře, ale ne obsahu".
     */
    private fun parsePayload(payload: ByteArray): Result {
        if (payload.size < HEADER) return Result.Unreadable
        val kind = payload[0]
        val senderMinor = payload[1].toInt() and 0xFF
        val buf = ByteBuffer.wrap(payload, 2, HEADER - 2)
        val timestamp = buf.long
        val len = buf.int
        // Odečítáme, ne přičítáme - HEADER + len by u obřího len přeteklo.
        if (len < 0 || len > payload.size - HEADER) return Result.Unreadable
        val data = payload.copyOfRange(HEADER, HEADER + len)

        // Trailer leží ZA daty. Když tam není nebo je poškozený, chováme se
        // jako by nebyl - zprávu kvůli vadné ozdobě nikdy nezahazujeme.
        val trailer = WireExt.parse(payload, HEADER + len)

        // Řídicí zpráva pro funkci, kterou neumíme -> tiše zahodit.
        //
        // POZOR na podmínku `len == 0`: zahazuje se JEN zpráva s prázdným tělem.
        // Řídicí zpráva ho podle kontraktu (viz WireExt.Control) prázdné mít
        // musí, takže se tím nic neztratí. Kdyby stačila jen přítomnost
        // příznaku, novější verze by mohla řídicí TLV pověsit na zprávu
        // s obsahem a starší appka by zahodila i ten obsah - nenávratně, protože
        // relay zprávu po potvrzení maže. U kousku souboru by navíc jeden
        // zahozený díl zasekl celý přenos napořád. Bezpečnost tady nesmí stát
        // na dohodě s budoucí verzí, ale na struktuře zprávy.
        // Prázdné tělo = zpráva bez obsahu pro uživatele. Buď je to řídicí
        // zpráva (reakce), nebo něco, čemu nerozumíme - v obou případech se
        // nemá co ztratit.
        //
        // POZOR: podmínka stojí na `len == 0`, NE na tom, že se povedlo přečíst
        // řídicí TLV. Kdyby se rozhodovalo podle traileru, stačil by vadný nebo
        // přerostlý trailer (parse vrací null) a spadlo by se do běžné větve,
        // kde by z prázdného textu vznikla prázdná bublina v historii - i s
        // notifikací a nepřečtenou zprávou o ničem.
        if (kind == KIND_TEXT && len == 0) {
            val control = trailer?.control
            if (control?.featureId == WireExt.FEATURE_REACTION) {
                val r = trailer.reaction
                if (r != null) {
                    return Result.Ok(
                        senderMinor,
                        Opened.Reaction(timestamp, r.targetHex, r.emoji, r.remove),
                        trailer.msgIdHex,
                        maxMajor = trailer.maxMajor,
                        capabilities = trailer.capabilities
                    )
                }
            }
            return Result.Unsupported(senderMinor)
        }

        val content: Opened? = when (kind) {
            KIND_IMAGE -> Opened.Image(timestamp, data)
            KIND_FILE_MANIFEST -> parseManifest(timestamp, data)
            KIND_FILE_CHUNK -> parseChunk(timestamp, data)
            KIND_TEXT -> Opened.Text(timestamp, String(data, Charsets.UTF_8))
            // Neznámý kind: TAHLE verze ho neumí, ale novější by mohla. Patří do
            // karantény, ne k zahození - jinak by aktualizace přišla pozdě a
            // zpráva už by nebyla odkud vzít.
            else -> return Result.Unreadable
        }
        // Vnitřek manifestu/kousku se rozparsovat nepovedl - poškozený obsah,
        // ať dostane šanci v karanténě.
        if (content == null) return Result.Unreadable
        return Result.Ok(
            senderMinor, content, trailer?.msgIdHex, trailer?.replyToHex,
            trailer?.maxMajor, trailer?.capabilities
        )
    }

    private fun parseManifest(timestamp: Long, data: ByteArray): Opened.FileManifest? {
        return try {
            val buf = ByteBuffer.wrap(data)
            val fileId = ByteArray(FILE_ID_BYTES).also { buf.get(it) }
            val totalChunks = buf.int
            val totalSize = buf.long
            val mime = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
            val name = ByteArray(buf.short.toInt() and 0xFFFF).also { buf.get(it) }
            if (totalChunks <= 0 || totalSize < 0) return null
            // Název i MIME plně řídí protějšek. Očisti je od obousměrných
            // přepínačů a řídicích znaků, ať RLO (U+202E) nezamaskuje příponu
            // v bublině (na disk se ukládá zvlášť sanitizovaný název).
            Opened.FileManifest(
                timestamp, fileId, totalChunks, totalSize,
                WireExt.sanitizeForDisplay(String(mime, Charsets.UTF_8)),
                WireExt.sanitizeForDisplay(String(name, Charsets.UTF_8))
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseChunk(timestamp: Long, data: ByteArray): Opened.FileChunk? {
        return try {
            if (data.size < FILE_ID_BYTES + 4) return null
            val buf = ByteBuffer.wrap(data)
            val fileId = ByteArray(FILE_ID_BYTES).also { buf.get(it) }
            val index = buf.int
            if (index < 0) return null
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            Opened.FileChunk(timestamp, fileId, index, bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeHeader(target: ByteArray, kind: Byte, timestamp: Long, dataLen: Int) {
        target[0] = kind
        target[1] = WireCompat.WIRE_MINOR.toByte()
        ByteBuffer.wrap(target, 2, HEADER - 2).putLong(timestamp).putInt(dataLen)
    }

    private fun encrypt(payload: ByteArray, keyBase64: String, dir: Int): ByteArray {
        val key = CryptoManager.keyFromBase64(keyBase64)
        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(dir, WireCompat.WIRE_MAJOR))
        return byteArrayOf(WireCompat.WIRE_MAJOR.toByte()) + iv + cipher.doFinal(payload)
    }

    /**
     * Přidružená data pro GCM: doménový štítek, směr schránky a verze formátu.
     * Nešifruje se, ale je součástí autentizačního tagu - blob zapsaný pro jeden
     * směr tedy nelze vydávat za blob směru opačného a otevřený bajt verze nejde
     * po cestě přepsat (rozbilo by to tag).
     */
    private fun aad(dir: Int, wire: Int): ByteArray =
        "ccdir:$dir|w:$wire".toByteArray(Charsets.US_ASCII)

    // --- Ratchet obálka (major 4) - viz RATCHET_WIRE.md ---

    // Otevřená hlavička: major(1) + htype(1) + epoch(4 BE) + msgNo(4 BE).
    private const val RATCHET_HEADER = 10
    private const val RATCHET_HTYPE_PLAIN: Byte = 0

    private fun ratchetHeaderBytes(epoch: Int, msgNo: Int): ByteArray {
        val h = ByteArray(RATCHET_HEADER)
        h[0] = WireCompat.WIRE_MAJOR_RATCHET.toByte()
        h[1] = RATCHET_HTYPE_PLAIN
        ByteBuffer.wrap(h, 2, 8).putInt(epoch).putInt(msgNo)
        return h
    }

    /**
     * AAD ratchet obálky: doménový štítek, směr a CELÁ čitelná hlavička (major,
     * htype, epocha, pořadí - a ve Fázi 4 i KEM oddíl). Relay tak nemůže nic
     * z toho přehodit ani podvrhnout (rozbil by GCM tag).
     */
    private fun ratchetAad(dir: Int, header: ByteArray): ByteArray =
        "ccr|dir=$dir".toByteArray(Charsets.US_ASCII) + header

    /** Rozparsovaná otevřená hlavička ratchet blobu (bez dešifrování). */
    data class RatchetHeader(
        val epoch: Int,
        val msgNo: Int,
        val htype: Int,
        val ciphertextOffset: Int
    )

    /**
     * Zašifruje připravený vnitřní [payload] (z build* funkcí) do RATCHET obálky
     * (major 4). Klíč i IV pocházejí z [DoubleRatchet.SendStep]; IV se NEPŘENÁŠÍ,
     * je odvozený z klíče zprávy, který je unikátní per (směr, msgNo).
     */
    internal fun encryptRatchet(
        payload: ByteArray,
        aesKey: ByteArray,
        iv: ByteArray,
        epoch: Int,
        msgNo: Int,
        dir: Int
    ): ByteArray {
        val header = ratchetHeaderBytes(epoch, msgNo)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(ratchetAad(dir, header))
        return header + cipher.doFinal(payload)
    }

    /**
     * Přečte otevřenou hlavičku ratchet blobu (major 4) BEZ dešifrování - z ní
     * volající vezme epochu a pořadí pro [DoubleRatchet.recvKey]. Vrací null, když
     * blob na ratchet formát nevypadá, je useknutý, nese neznámý příznak hlavičky
     * (např. KEM oddíl z Fáze 4, který tahle verze ještě neumí → karanténa) nebo
     * má nesmyslná (záporná) pole.
     */
    fun readRatchetHeader(blob: ByteArray): RatchetHeader? {
        if (blob.size < RATCHET_HEADER + GCM_TAG_BITS / 8) return null
        if ((blob[0].toInt() and 0xFF) != WireCompat.WIRE_MAJOR_RATCHET) return null
        val htype = blob[1].toInt() and 0xFF
        // Zatím umíme jen prostou hlavičku. Neznámý příznak (KEM oddíl, Fáze 4) →
        // null → karanténa, po aktualizaci se blob přečte.
        if (htype != RATCHET_HTYPE_PLAIN.toInt()) return null
        val buf = ByteBuffer.wrap(blob, 2, 8)
        val epoch = buf.int
        val msgNo = buf.int
        if (epoch < 0 || msgNo < 0) return null
        return RatchetHeader(epoch, msgNo, htype, RATCHET_HEADER)
    }

    /**
     * Dešifruje a rozbalí RATCHET blob (major 4). Klíč+IV dodá volající
     * z [DoubleRatchet.recvKey] (podle epochy a pořadí z [readRatchetHeader]).
     * Vnitřek se parsuje stejným [parsePayload] jako legacy - `kind`, trailer,
     * capability i reakce fungují identicky. Vrací tři stavy jako [open].
     */
    fun openRatchet(blob: ByteArray, aesKey: ByteArray, iv: ByteArray, dir: Int): Result {
        val header = readRatchetHeader(blob) ?: return Result.Unreadable
        val payload = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(ratchetAad(dir, blob.copyOfRange(0, header.ciphertextOffset)))
            cipher.doFinal(blob.copyOfRange(header.ciphertextOffset, blob.size))
        } catch (e: Exception) {
            return Result.Unreadable
        }
        return try {
            parsePayload(payload)
        } catch (e: Exception) {
            Result.Unreadable
        }
    }

    /** Nejbližší koš >= potřebné velikosti; nad nejvyšší koš zaokrouhlí na jeho násobek. */
    // internal (ne private) kvůli přímému testu guardu proti přetečení - vstup,
    // který ho spustí (~2 GB), nejde vyrobit reálnou zprávou (viz nález v1.1-8).
    internal fun bucketFor(size: Int): Int {
        BUCKETS.firstOrNull { it >= size }?.let { return it }
        val top = BUCKETS.last()
        // Zaokrouhlení nahoru by u velikosti blízko Int.MAX_VALUE přeteklo do
        // záporného čísla a ByteArray() by spadlo. Nedosažitelné (limit relaye),
        // ale zbytek formátu je proti přetečení bráněný, ať to nevyčnívá.
        if (size > Int.MAX_VALUE - top) return Int.MAX_VALUE
        return ((size + top - 1) / top) * top
    }
}
