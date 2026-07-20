package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.CryptoManager
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

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
 *   [1B kind][8B timestamp BE][4B délka dat BE][data][(u textu) výplň nulami]
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
    private const val HEADER = 13 // 1B kind + 8B timestamp + 4B délka

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
    }

    /** Zabalí a zašifruje textovou zprávu (s paddingem přes koše). */
    fun seal(text: String, timestamp: Long, keyBase64: String, dir: Int): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        val payloadLen = HEADER + data.size
        val padded = ByteArray(bucketFor(payloadLen))
        writeHeader(padded, KIND_TEXT, timestamp, data.size)
        System.arraycopy(data, 0, padded, HEADER, data.size)
        return encrypt(padded, keyBase64, dir)
    }

    /** Zabalí a zašifruje fotku (JPEG bajty, bez paddingu). */
    fun sealImage(jpeg: ByteArray, timestamp: Long, keyBase64: String, dir: Int): ByteArray {
        val payload = ByteArray(HEADER + jpeg.size)
        writeHeader(payload, KIND_IMAGE, timestamp, jpeg.size)
        System.arraycopy(jpeg, 0, payload, HEADER, jpeg.size)
        return encrypt(payload, keyBase64, dir)
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
        val mime = mimeType.toByteArray(Charsets.UTF_8)
        val name = fileName.toByteArray(Charsets.UTF_8)
        val data = ByteBuffer.allocate(FILE_ID_BYTES + 4 + 8 + 2 + mime.size + 2 + name.size)
        data.put(fileId).putInt(totalChunks).putLong(totalSize)
        data.putShort(mime.size.toShort()).put(mime)
        data.putShort(name.size.toShort()).put(name)
        return sealRaw(KIND_FILE_MANIFEST, data.array(), timestamp, keyBase64, dir)
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
        val data = ByteBuffer.allocate(FILE_ID_BYTES + 4 + chunk.size)
        data.put(fileId).putInt(index).put(chunk)
        return sealRaw(KIND_FILE_CHUNK, data.array(), timestamp, keyBase64, dir)
    }

    private fun sealRaw(
        kind: Byte,
        data: ByteArray,
        timestamp: Long,
        keyBase64: String,
        dir: Int
    ): ByteArray {
        val payload = ByteArray(HEADER + data.size)
        writeHeader(payload, kind, timestamp, data.size)
        System.arraycopy(data, 0, payload, HEADER, data.size)
        return encrypt(payload, keyBase64, dir)
    }

    /**
     * Dešifruje a rozbalí blob. Vrátí null, když blob nesedí (cizí klíč,
     * poškození, jiný formát) - volající ho pak jen zahodí.
     */
    fun open(blob: ByteArray, keyBase64: String, dir: Int): Opened? {
        return try {
            // [1B verze formátu][12B IV][ciphertext+tag]
            if (blob.size <= 1 + IV_SIZE_BYTES) return null
            val wire = blob[0].toInt() and 0xFF
            if (wire != WireCompat.WIRE_VERSION) return null
            val iv = blob.copyOfRange(1, 1 + IV_SIZE_BYTES)
            val cipherBytes = blob.copyOfRange(1 + IV_SIZE_BYTES, blob.size)
            val key = CryptoManager.keyFromBase64(keyBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad(dir, wire))
            val payload = cipher.doFinal(cipherBytes)
            if (payload.size < HEADER) return null
            val kind = payload[0]
            val buf = ByteBuffer.wrap(payload, 1, HEADER - 1)
            val timestamp = buf.long
            val len = buf.int
            if (len < 0 || HEADER + len > payload.size) return null
            val data = payload.copyOfRange(HEADER, HEADER + len)
            when (kind) {
                KIND_IMAGE -> Opened.Image(timestamp, data)
                KIND_FILE_MANIFEST -> parseManifest(timestamp, data)
                KIND_FILE_CHUNK -> parseChunk(timestamp, data)
                KIND_TEXT -> Opened.Text(timestamp, String(data, Charsets.UTF_8))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
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
            Opened.FileManifest(
                timestamp, fileId, totalChunks, totalSize,
                String(mime, Charsets.UTF_8), String(name, Charsets.UTF_8)
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
        ByteBuffer.wrap(target, 1, HEADER - 1).putLong(timestamp).putInt(dataLen)
    }

    private fun encrypt(payload: ByteArray, keyBase64: String, dir: Int): ByteArray {
        val key = CryptoManager.keyFromBase64(keyBase64)
        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(dir, WireCompat.WIRE_VERSION))
        return byteArrayOf(WireCompat.WIRE_VERSION.toByte()) + iv + cipher.doFinal(payload)
    }

    /**
     * Přidružená data pro GCM: doménový štítek, směr schránky a verze formátu.
     * Nešifruje se, ale je součástí autentizačního tagu - blob zapsaný pro jeden
     * směr tedy nelze vydávat za blob směru opačného a otevřený bajt verze nejde
     * po cestě přepsat (rozbilo by to tag).
     */
    private fun aad(dir: Int, wire: Int): ByteArray =
        "ccdir:$dir|w:$wire".toByteArray(Charsets.US_ASCII)

    /** Nejbližší koš >= potřebné velikosti; nad nejvyšší koš zaokrouhlí na jeho násobek. */
    private fun bucketFor(size: Int): Int {
        BUCKETS.firstOrNull { it >= size }?.let { return it }
        val top = BUCKETS.last()
        return ((size + top - 1) / top) * top
    }
}
