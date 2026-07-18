package com.example.cryptochat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy post-kvantové výměny klíče (ML-KEM-768 + HKDF). BouncyCastle je čistá
 * Java, takže testy běží na JVM bez emulátoru.
 */
class PostQuantumKemTest {

    @Test
    fun obeStrany_odvodiStejnyKlicAKod() {
        val initiator = PostQuantumKem.generateKeyPair()
        val responder = PostQuantumKem.encapsulate(initiator.publicKeyBase64)
        val initiatorKeys =
            PostQuantumKem.decapsulate(initiator.privateKeyBase64, responder.encapsulationBase64)

        assertEquals(responder.sharedKeys.aesKeyBase64, initiatorKeys.aesKeyBase64)
        assertEquals(responder.sharedKeys.verificationCode, initiatorKeys.verificationCode)
    }

    @Test
    fun odvozenyAesKlic_ma32Bajtu() {
        val kp = PostQuantumKem.generateKeyPair()
        val result = PostQuantumKem.encapsulate(kp.publicKeyBase64)
        assertEquals(32, Base64Util.decode(result.sharedKeys.aesKeyBase64).size)
    }

    @Test
    fun ruzneVymeny_daji_ruzneKlice() {
        val kp = PostQuantumKem.generateKeyPair()
        val a = PostQuantumKem.encapsulate(kp.publicKeyBase64)
        val b = PostQuantumKem.encapsulate(kp.publicKeyBase64)
        assertNotEquals(a.sharedKeys.aesKeyBase64, b.sharedKeys.aesKeyBase64)
    }

    @Test
    fun aesKlicASas_jsouRuzne_domenovaSeparace() {
        // SAS nesmí být jen hex prefixu AES klíče (to by dělal naivní návrh
        // bez doménové separace). Díky HKDF s různými labely se liší.
        val kp = PostQuantumKem.generateKeyPair()
        val result = PostQuantumKem.encapsulate(kp.publicKeyBase64)
        val aesBytes = Base64Util.decode(result.sharedKeys.aesKeyBase64)
        val aesHexPrefix = aesBytes.take(6)
            .joinToString("") { "%02x".format(it) }
            .uppercase()
            .chunked(4)
            .joinToString(" ")
        assertNotEquals(aesHexPrefix, result.sharedKeys.verificationCode)
    }

    @Test
    fun sasKod_ocekavanyFormat() {
        val kp = PostQuantumKem.generateKeyPair()
        val result = PostQuantumKem.encapsulate(kp.publicKeyBase64)
        // Např. "AB12 CD34 EF56" - 3 skupiny po 4 hex znacích.
        assertTrue(
            "Neočekávaný formát: ${result.sharedKeys.verificationCode}",
            result.sharedKeys.verificationCode.matches(Regex("[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}"))
        )
    }
}
