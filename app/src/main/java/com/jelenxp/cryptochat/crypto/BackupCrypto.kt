package com.jelenxp.cryptochat.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Šifrování zálohy kontaktů heslem. Z hesla se přes **PBKDF2-HMAC-SHA256**
 * (s náhodnou solí a mnoha iteracemi) odvodí AES-256 klíč, kterým se data
 * zašifrují **AES-256-GCM** (autentizované šifrování).
 *
 * Formát bajtů souboru zálohy:
 * `MAGIC[4] || salt[16] || IV[12] || ciphertext+GCM tag`
 *
 * Bez správného hesla zálohu nikdo nerozšifruje - a protože se klíč odvozuje
 * z hesla (ne z ničeho uloženého na zařízení), jde zálohu obnovit i na jiném
 * telefonu. Zapomenuté heslo ale znamená nenávratně ztracenou zálohu.
 *
 * Používá `javax.crypto` (standardní Java), takže je to testovatelné na JVM.
 */
object BackupCrypto {

    // "CCB" + verze formátu (CryptoChat Backup v1).
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 1)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Zašifruje data heslem. Výstup je celý obsah souboru zálohy. */
    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plaintext)
        return MAGIC + salt + iv + cipherBytes
    }

    /**
     * Dešifruje zálohu. Vyhodí výjimku při špatném hesle nebo poškozeném
     * souboru (GCM kontrola integrity) - volající to má odchytit a zobrazit
     * jako chybu „špatné heslo / poškozený soubor".
     */
    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        require(blob.size > MAGIC.size + SALT_LEN + IV_LEN) { "Soubor zálohy je příliš krátký." }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Neplatný formát zálohy." }
        var offset = MAGIC.size
        val salt = blob.copyOfRange(offset, offset + SALT_LEN); offset += SALT_LEN
        val iv = blob.copyOfRange(offset, offset + IV_LEN); offset += IV_LEN
        val cipherBytes = blob.copyOfRange(offset, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }
}
