package com.jelenxp.cryptochat.crypto

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Jádro šifrovací logiky aplikace.
 *
 * Koncept:
 *  - Dva uživatelé si mimo aplikaci (QR kód / zkopírovaný text) vymění jeden
 *    sdílený symetrický klíč (AES-256).
 *  - Zprávy se pak šifrují/dešifrují pomocí AES/GCM (autentizované šifrování,
 *    zaručuje důvěrnost i integritu - pokud je zpráva pozměněná nebo klíč
 *    nesedí, dešifrování selže).
 *  - Výsledek šifrování (IV + šifrový text + autentizační tag) se zabalí
 *    do jednoho Base64 řetězce, který lze zkopírovat a poslat např. přes SMS,
 *    e-mail nebo jakoukoliv jinou (nedůvěryhodnou) cestu.
 */
object CryptoManager {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // První bajt otevřeného textu (uvnitř šifry, chráněný GCM tagem) říká, jestli
    // je zbytek zkomprimovaný (Deflate). Komprese se použije jen když je výsledek
    // menší, takže výstup nikdy není větší než dřív o víc než 1 bajt.
    private const val FLAG_RAW: Byte = 0
    private const val FLAG_DEFLATE: Byte = 1

    /** Vygeneruje nový náhodný AES-256 klíč. */
    fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_SIZE_BITS, SecureRandom())
        return generator.generateKey()
    }

    fun keyToBase64(key: SecretKey): String =
        Base64Util.encode(key.encoded)

    /** Může vyhodit IllegalArgumentException, pokud text není platný Base64 / správná délka klíče. */
    fun keyFromBase64(base64Key: String): SecretKey {
        val bytes = Base64Util.decode(base64Key)
        require(bytes.size == 32) { "Klíč musí mít 256 bitů (32 bajtů)." }
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * Krátký ověřovací otisk sdíleného klíče. Obě strany mají stejný AES klíč,
     * takže spočítají stejný otisk - můžou si ho kdykoli porovnat jiným kanálem
     * a ověřit, že mají opravdu tentýž klíč (odhalí podvržení/záměnu). Je to
     * SHA-256 z klíče (s doménovým prefixem) zkrácená na 8 bajtů, takže z otisku
     * nejde klíč zpětně odvodit.
     */
    fun fingerprint(keyBase64: String): String {
        val keyBytes = Base64Util.decode(keyBase64)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("CryptoChat-key-fingerprint-v1".toByteArray(Charsets.UTF_8))
        val hash = digest.digest(keyBytes)
        val hex = hash.take(8).joinToString("") { "%02x".format(it) }
        return hex.uppercase().chunked(4).joinToString(" ")
    }

    /** Zašifruje text a vrátí Base64 řetězec (IV + ciphertext + tag). */
    fun encrypt(plainText: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val payload = pack(plainText.toByteArray(Charsets.UTF_8))
        val cipherBytes = cipher.doFinal(payload)
        return Base64Util.encode(iv + cipherBytes)
    }

    /**
     * Dešifruje Base64 řetězec vytvořený funkcí [encrypt].
     * Vyhodí výjimku, pokud je zpráva poškozená nebo klíč nesedí -
     * to je záměrné a dokazuje, že integrita zprávy je ověřená (GCM tag).
     */
    fun decrypt(payloadBase64: String, key: SecretKey): String {
        val combined = Base64Util.decode(payloadBase64)
        require(combined.size > IV_SIZE_BYTES) { "Zpráva je příliš krátká." }
        val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
        val cipherBytes = combined.copyOfRange(IV_SIZE_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(unpack(plainBytes), Charsets.UTF_8)
    }

    /**
     * Zabalí otevřený text před zašifrováním: zkusí Deflate a použije ho, jen
     * když je výsledek menší. Vrací `FLAG || data` (příznak je pak uvnitř šifry
     * a chráněný GCM tagem).
     */
    private fun pack(raw: ByteArray): ByteArray {
        val compressed = deflate(raw)
        return if (compressed.size < raw.size) {
            byteArrayOf(FLAG_DEFLATE) + compressed
        } else {
            byteArrayOf(FLAG_RAW) + raw
        }
    }

    /** Rozbalí to, co vytvořil [pack]. Neznámý příznak = jiný/starší formát -> chyba. */
    private fun unpack(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Prázdný obsah zprávy." }
        val flag = payload[0]
        val data = payload.copyOfRange(1, payload.size)
        return when (flag) {
            FLAG_DEFLATE -> inflate(data)
            FLAG_RAW -> data
            else -> throw IllegalArgumentException("Neznámý formát zprávy.")
        }
    }

    /** Raw Deflate (bez zlib hlavičky, minimální režie). */
    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream(maxOf(32, input.size / 2))
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            output.write(buffer, 0, n)
        }
        deflater.end()
        return output.toByteArray()
    }

    /** Raw Inflate (protějšek [deflate]). */
    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(input)
        val output = ByteArrayOutputStream(maxOf(32, input.size * 2))
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) break
            output.write(buffer, 0, n)
        }
        inflater.end()
        return output.toByteArray()
    }
}
