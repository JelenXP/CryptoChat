package com.jelenxp.cryptochat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy šifrování zpráv (AES-256-GCM). Běží na čistém JVM - javax.crypto
 * AES/GCM je součástí standardní Javy, android.util.Base64 se nepoužívá.
 */
class CryptoManagerTest {

    @Test
    fun sifrovani_desifrovani_roundTrip() {
        val key = CryptoManager.generateKey()
        val message = "Ahoj, tajná zpráva s diakritikou: příliš žluťoučký kůň 🐴"
        val cipher = CryptoManager.encrypt(message, key)
        assertEquals(message, CryptoManager.decrypt(cipher, key))
    }

    @Test
    fun spatnyKlic_desifrovaniSelze() {
        val key1 = CryptoManager.generateKey()
        val key2 = CryptoManager.generateKey()
        val cipher = CryptoManager.encrypt("secret", key1)
        assertThrows(Exception::class.java) { CryptoManager.decrypt(cipher, key2) }
    }

    @Test
    fun poskozenyText_desifrovaniSelze() {
        val key = CryptoManager.generateKey()
        val cipher = CryptoManager.encrypt("secret", key)
        // Poškodíme poslední bajt (součást GCM tagu) - integrita musí selhat.
        val bytes = Base64Util.decode(cipher)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        val tampered = Base64Util.encode(bytes)
        assertThrows(Exception::class.java) { CryptoManager.decrypt(tampered, key) }
    }

    @Test
    fun klic_base64_roundTrip() {
        val key = CryptoManager.generateKey()
        val restored = CryptoManager.keyFromBase64(CryptoManager.keyToBase64(key))
        val cipher = CryptoManager.encrypt("hello", key)
        // Obnovený klíč musí umět dešifrovat, co zašifroval původní.
        assertEquals("hello", CryptoManager.decrypt(cipher, restored))
    }

    @Test
    fun klic_spatnaDelka_odmitnut() {
        val tooShort = Base64Util.encode(ByteArray(16)) // 128 bitů místo 256
        assertThrows(IllegalArgumentException::class.java) {
            CryptoManager.keyFromBase64(tooShort)
        }
    }

    @Test
    fun kazdeSifrovani_jineIV() {
        val key = CryptoManager.generateKey()
        val c1 = CryptoManager.encrypt("same", key)
        val c2 = CryptoManager.encrypt("same", key)
        // Náhodné IV => stejný text dá pokaždé jiný šifrový výstup.
        assertNotEquals(c1, c2)
    }

    @Test
    fun dlouhaZprava_roundTrip() {
        val key = CryptoManager.generateKey()
        val message = "Toto je delší zpráva s diakritikou žluťoučký kůň. ".repeat(30)
        assertEquals(message, CryptoManager.decrypt(CryptoManager.encrypt(message, key), key))
    }

    @Test
    fun stlacitelnaZprava_seZmensi() {
        val key = CryptoManager.generateKey()
        val message = "A".repeat(1000) // silně stlačitelné
        val encrypted = CryptoManager.encrypt(message, key)
        // Díky kompresi je šifrovaný Base64 výstup kratší než původní text.
        assertTrue(encrypted.length < message.length)
    }
}
