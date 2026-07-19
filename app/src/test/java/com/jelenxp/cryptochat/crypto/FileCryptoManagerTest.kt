package com.jelenxp.cryptochat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Testy šifrování souborů/binárních dat (AES-256-GCM, [FileCryptoManager]).
 * Běží na čistém JVM - jen javax.crypto, žádné android.* API.
 *
 * Různé "typy souborů" simulujeme reprezentativními bajtovými vzorky (hlavičky
 * PNG/JPEG/PDF + náhodná/opakující se data), abychom ověřili, že round-trip
 * funguje pro libovolný obsah a že integrita (GCM tag) opravdu drží.
 */
class FileCryptoManagerTest {

    private fun key() = CryptoManager.generateKey()

    /** Deterministická "náhodná" data dané velikosti (stabilní seed = opakovatelný test). */
    private fun bytes(size: Int, seed: Long = 42): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    @Test
    fun bajty_roundTrip_ruzneObsahy() {
        val key = key()
        val samples = listOf(
            ByteArray(0),                                   // prázdný soubor
            byteArrayOf(0),                                 // jeden nulový bajt
            "Prostý text: příliš žluťoučký kůň".toByteArray(Charsets.UTF_8),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),   // PNG signatura
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()), // JPEG SOI
            "%PDF-1.7".toByteArray(Charsets.US_ASCII),      // PDF hlavička
            bytes(1),
            bytes(15),
            bytes(16),
            bytes(4096)
        )
        for (data in samples) {
            val enc = FileCryptoManager.encrypt(data, key)
            val dec = FileCryptoManager.decrypt(enc, key)
            assertArrayEquals("Round-trip selhal pro ${data.size} B", data, dec)
        }
    }

    @Test
    fun bajty_velkyObrazek_roundTrip() {
        val key = key()
        // ~5 MB pseudonáhodných dat = simulace většího (nekomprimovatelného) obrázku.
        val image = bytes(5 * 1024 * 1024)
        val enc = FileCryptoManager.encrypt(image, key)
        assertArrayEquals(image, FileCryptoManager.decrypt(enc, key))
    }

    @Test
    fun spatnyKlic_desifrovaniSelze() {
        val enc = FileCryptoManager.encrypt(bytes(1000), key())
        assertThrows(Exception::class.java) { FileCryptoManager.decrypt(enc, key()) }
    }

    @Test
    fun poskozenaData_desifrovaniSelze() {
        val key = key()
        val enc = FileCryptoManager.encrypt(bytes(1000), key)
        enc[enc.size - 1] = (enc[enc.size - 1] + 1).toByte() // poškodíme GCM tag
        assertThrows(Exception::class.java) { FileCryptoManager.decrypt(enc, key) }
    }

    @Test
    fun kazdeSifrovani_jineIV() {
        val key = key()
        val data = bytes(256)
        val a = FileCryptoManager.encrypt(data, key)
        val b = FileCryptoManager.encrypt(data, key)
        assertFalse("Stejný výstup => IV se nemění", a.contentEquals(b))
    }

    @Test
    fun klicVBase64_funguje() {
        val keyB64 = CryptoManager.keyToBase64(key())
        val data = bytes(2048)
        val enc = FileCryptoManager.encrypt(data, keyB64)
        assertArrayEquals(data, FileCryptoManager.decrypt(enc, keyB64))
    }

    @Test
    fun stream_roundTrip() {
        val key = key()
        val data = bytes(1024 * 1024) // 1 MB
        val encOut = ByteArrayOutputStream()
        FileCryptoManager.encryptStream(ByteArrayInputStream(data), encOut, key)
        val decOut = ByteArrayOutputStream()
        FileCryptoManager.decryptStream(ByteArrayInputStream(encOut.toByteArray()), decOut, key)
        assertArrayEquals(data, decOut.toByteArray())
    }

    @Test
    fun stream_a_bajty_jsouKompatibilni() {
        val key = key()
        val data = bytes(50_000)
        // Zašifrováno streamem -> dešifrovatelné bajtovým API (stejný formát).
        val encOut = ByteArrayOutputStream()
        FileCryptoManager.encryptStream(ByteArrayInputStream(data), encOut, key)
        assertArrayEquals(data, FileCryptoManager.decrypt(encOut.toByteArray(), key))

        // A naopak: zašifrováno bajtově -> dešifrovatelné streamem.
        val enc = FileCryptoManager.encrypt(data, key)
        val decOut = ByteArrayOutputStream()
        FileCryptoManager.decryptStream(ByteArrayInputStream(enc), decOut, key)
        assertArrayEquals(data, decOut.toByteArray())
    }

    @Test
    fun prilisKratkyVstup_odmitnut() {
        assertThrows(Exception::class.java) {
            FileCryptoManager.decrypt(ByteArray(5), CryptoManager.keyToBase64(key()))
        }
    }
}
