package com.jelenxp.cryptochat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Testy kontejneru s metadaty souboru ([FileContainer]) - včetně kombinace
 * s [FileCryptoManager] (přesně to, co dělá UI: pack -> encrypt -> decrypt -> unpack).
 */
class FileContainerTest {

    @Test
    fun pack_unpack_roundTrip() {
        val data = ByteArray(500) { (it % 256).toByte() }
        val u = FileContainer.unpack(FileContainer.pack("foto.png", "image/png", data))
        assertEquals("foto.png", u.name)
        assertEquals("image/png", u.mime)
        assertArrayEquals(data, u.data)
    }

    @Test
    fun prazdneMime_jeNull() {
        val u = FileContainer.unpack(FileContainer.pack("a.bin", null, byteArrayOf(1, 2, 3)))
        assertNull(u.mime)
        assertEquals("a.bin", u.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), u.data)
    }

    @Test
    fun jmenoSDiakritikou_aEmoji() {
        val u = FileContainer.unpack(FileContainer.pack("žluťoučký 🐴.jpg", "image/jpeg", ByteArray(0)))
        assertEquals("žluťoučký 🐴.jpg", u.name)
        assertEquals("image/jpeg", u.mime)
        assertArrayEquals(ByteArray(0), u.data)
    }

    @Test
    fun cizirFormat_vyhodiVyjimku() {
        assertThrows(Exception::class.java) { FileContainer.unpack(byteArrayOf(9, 9, 9)) }
    }

    @Test
    fun prazdnyVstup_vyhodiVyjimku() {
        assertThrows(Exception::class.java) { FileContainer.unpack(ByteArray(0)) }
    }

    @Test
    fun cely_tok_pack_encrypt_decrypt_unpack() {
        val key = CryptoManager.generateKey()
        val data = ByteArray(4096) { ((it * 7) % 256).toByte() }
        val packed = FileContainer.pack("dokument.pdf", "application/pdf", data)
        val encrypted = FileCryptoManager.encrypt(packed, key)
        val u = FileContainer.unpack(FileCryptoManager.decrypt(encrypted, key))
        assertEquals("dokument.pdf", u.name)
        assertEquals("application/pdf", u.mime)
        assertArrayEquals(data, u.data)
    }
}
