package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.CryptoManager
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Obálka jedné chatové zprávy pro přenos přes relay. Zpráva se zašifruje
 * AES-256-GCM sdíleným klíčem kontaktu (stejným, jaký používá [CryptoManager]),
 * takže relay ani nikdo po cestě nevidí obsah.
 *
 * **Skrytí délky:** otevřený obsah se před zašifrováním doplní (padding) na
 * nejbližší „koš" fixní velikosti. Server ani odposlech pak z velikosti blobu
 * nepozná, jestli šlo o „ok" nebo dlouhý odstavec.
 *
 * Formát otevřeného obsahu (uvnitř šifry, chráněný GCM tagem):
 *   [8B timestamp odeslání BE][4B délka textu BE][UTF-8 text][výplň nulami]
 *
 * Výstupní blob: `IV[12] || ciphertext || GCM tag[16]` (syrové bajty, ne Base64 -
 * relay přenáší bajty).
 */
object ChatEnvelope {

    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val HEADER = 12 // 8B timestamp + 4B délka

    // Koše pro padding (bajty). Malé zprávy spadnou do 256 B; větší se zaokrouhlí nahoru.
    private val BUCKETS = intArrayOf(256, 1024, 4096, 16_384, 65_536, 262_144)

    data class Opened(val timestamp: Long, val text: String)

    /** Zabalí a zašifruje zprávu. `timestamp` = čas odeslání (epoch millis). */
    fun seal(text: String, timestamp: Long, keyBase64: String): ByteArray {
        val msg = text.toByteArray(Charsets.UTF_8)
        val payloadLen = HEADER + msg.size
        val padded = ByteArray(bucketFor(payloadLen))
        ByteBuffer.wrap(padded).putLong(timestamp).putInt(msg.size)
        System.arraycopy(msg, 0, padded, HEADER, msg.size)

        val key = CryptoManager.keyFromBase64(keyBase64)
        val iv = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(padded)
    }

    /**
     * Dešifruje a rozbalí blob. Vrátí null, když blob nesedí (cizí klíč,
     * poškození, jiný formát) - volající ho pak jen zahodí.
     */
    fun open(blob: ByteArray, keyBase64: String): Opened? {
        return try {
            if (blob.size <= IV_SIZE_BYTES) return null
            val iv = blob.copyOfRange(0, IV_SIZE_BYTES)
            val cipherBytes = blob.copyOfRange(IV_SIZE_BYTES, blob.size)
            val key = CryptoManager.keyFromBase64(keyBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val padded = cipher.doFinal(cipherBytes)
            if (padded.size < HEADER) return null
            val buf = ByteBuffer.wrap(padded)
            val timestamp = buf.long
            val len = buf.int
            if (len < 0 || HEADER + len > padded.size) return null
            val text = String(padded, HEADER, len, Charsets.UTF_8)
            Opened(timestamp, text)
        } catch (e: Exception) {
            null
        }
    }

    /** Nejbližší koš >= potřebné velikosti; nad nejvyšší koš zaokrouhlí na jeho násobek. */
    private fun bucketFor(size: Int): Int {
        BUCKETS.firstOrNull { it >= size }?.let { return it }
        val top = BUCKETS.last()
        return ((size + top - 1) / top) * top
    }
}
