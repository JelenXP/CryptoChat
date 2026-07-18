package com.jelenxp.cryptochat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Testy šifrování zálohy heslem (PBKDF2 + AES-GCM). Běží na čistém JVM. */
class BackupCryptoTest {

    @Test
    fun roundTrip_spravneHeslo() {
        val data = "kontakty: Bob, klíč ABC123 🔐".toByteArray()
        val blob = BackupCrypto.encrypt(data, "tajneHeslo123".toCharArray())
        assertArrayEquals(data, BackupCrypto.decrypt(blob, "tajneHeslo123".toCharArray()))
    }

    @Test
    fun spatneHeslo_selze() {
        val blob = BackupCrypto.encrypt("secret".toByteArray(), "spravne".toCharArray())
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(blob, "spatne".toCharArray())
        }
    }

    @Test
    fun poskozenySoubor_selze() {
        val blob = BackupCrypto.encrypt("secret".toByteArray(), "pw".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(blob, "pw".toCharArray()) }
    }

    @Test
    fun kazdyExport_jinyVystup() {
        val a = BackupCrypto.encrypt("x".toByteArray(), "pw".toCharArray())
        val b = BackupCrypto.encrypt("x".toByteArray(), "pw".toCharArray())
        // Náhodná sůl i IV → stejná data + heslo dají pokaždé jiný výstup.
        assertFalse(a.contentEquals(b))
    }
}
