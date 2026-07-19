package com.jelenxp.cryptochat

import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.crypto.FileStreamCipher
import com.jelenxp.cryptochat.crypto.PostQuantumKem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Testy FUNKČNOSTI aplikace - simulují reálné scénáře end-to-end přes skutečné
 * produkční třídy (ne jednotlivé metody izolovaně). Ověřují, že hlavní uživatelské
 * toky opravdu fungují. Běží na čistém JVM (bez emulátoru/Androidu), takže se
 * pouští automaticky při každém buildu v CI (`gradle testDebugUnitTest`).
 */
class AppFunctionalityTest {

    private fun bytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    /** Osobní výměna klíče (QR/text): Alice vytvoří klíč, Bob ho přijme -> píšou si oběma směry. */
    @Test
    fun osobniVymena_obousmerneZpravy() {
        val sharedKeyBase64 = CryptoManager.keyToBase64(CryptoManager.generateKey())
        val aliceKey = CryptoManager.keyFromBase64(sharedKeyBase64)
        val bobKey = CryptoManager.keyFromBase64(sharedKeyBase64)

        val fromAlice = "Ahoj Bobe, příliš žluťoučký kůň 🐴"
        assertEquals(fromAlice, CryptoManager.decrypt(CryptoManager.encrypt(fromAlice, aliceKey), bobKey))

        val fromBob = "Ahoj Alice, tady Bob."
        assertEquals(fromBob, CryptoManager.decrypt(CryptoManager.encrypt(fromBob, bobKey), aliceKey))
    }

    /** Cizí kontakt (jiný klíč) zprávu nepřečte - GCM ověření integrity. */
    @Test
    fun zpravu_cizimKlicem_nelzePrecist() {
        val enc = CryptoManager.encrypt("tajná zpráva", CryptoManager.generateKey())
        assertThrows(Exception::class.java) { CryptoManager.decrypt(enc, CryptoManager.generateKey()) }
    }

    /** Vzdálená výměna (ML-KEM): obě strany odvodí stejný klíč i SAS a pak si napíšou. */
    @Test
    fun vzdalenaVymena_stejnyKlicSasAZpravy() {
        val initiator = PostQuantumKem.generateKeyPair()
        val responder = PostQuantumKem.encapsulate(initiator.publicKeyBase64)
        val initiatorKeys = PostQuantumKem.decapsulate(initiator.privateKeyBase64, responder.encapsulationBase64)

        assertEquals(responder.sharedKeys.aesKeyBase64, initiatorKeys.aesKeyBase64)
        assertEquals(responder.sharedKeys.verificationCode, initiatorKeys.verificationCode)

        // Odvozeným klíčem se dá reálně poslat zpráva.
        val a = CryptoManager.keyFromBase64(initiatorKeys.aesKeyBase64)
        val b = CryptoManager.keyFromBase64(responder.sharedKeys.aesKeyBase64)
        val msg = "Zpráva po post-kvantové výměně."
        assertEquals(msg, CryptoManager.decrypt(CryptoManager.encrypt(msg, a), b))
    }

    /** Poslání a přijetí souboru (streamově, přes hranice rámců) - obnoví se i jméno a MIME. */
    @Test
    fun posilaniSouboru_endToEnd() {
        val key = CryptoManager.generateKey()
        val original = bytes(3 * 1024 * 1024 + 123) // ~3 MB = víc rámců
        val name = "dovolená.jpg"
        val mime = "image/jpeg"

        // Odesílatel (SendFileScreen)
        val transmitted = ByteArrayOutputStream()
        FileStreamCipher.encrypt(ByteArrayInputStream(original), transmitted, key, name, mime, Long.MAX_VALUE) {}

        // Příjemce (ReceiveFileScreen)
        val received = ByteArrayOutputStream()
        val meta = FileStreamCipher.decrypt(ByteArrayInputStream(transmitted.toByteArray()), received, key, Long.MAX_VALUE) {}

        assertArrayEquals(original, received.toByteArray())
        assertEquals(name, meta.name)
        assertEquals(mime, meta.mime)
    }

    /** Cizí klíč soubor nedešifruje. */
    @Test
    fun soubor_cizimKlicem_selze() {
        val transmitted = ByteArrayOutputStream()
        FileStreamCipher.encrypt(ByteArrayInputStream(bytes(2000)), transmitted, CryptoManager.generateKey(), "x.bin", null, Long.MAX_VALUE) {}
        assertThrows(Exception::class.java) {
            FileStreamCipher.decrypt(
                ByteArrayInputStream(transmitted.toByteArray()),
                ByteArrayOutputStream(),
                CryptoManager.generateKey(),
                Long.MAX_VALUE
            ) {}
        }
    }

    /** Otisk klíče (ověření kontaktu): stejný klíč -> obě strany vidí stejný kód; jiný klíč -> jiný. */
    @Test
    fun otiskKlice_stejnyProStejnyKlic_ruznyProJiny() {
        val k1 = CryptoManager.keyToBase64(CryptoManager.generateKey())
        val k2 = CryptoManager.keyToBase64(CryptoManager.generateKey())
        assertEquals(CryptoManager.fingerprint(k1), CryptoManager.fingerprint(k1))
        assertNotEquals(CryptoManager.fingerprint(k1), CryptoManager.fingerprint(k2))
    }
}
