package com.example.cryptochat.crypto

import java.security.SecureRandom
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

    /** Zašifruje text a vrátí Base64 řetězec (IV + ciphertext + tag). */
    fun encrypt(plainText: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherBytes
        return Base64Util.encode(combined)
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
        return String(plainBytes, Charsets.UTF_8)
    }
}
