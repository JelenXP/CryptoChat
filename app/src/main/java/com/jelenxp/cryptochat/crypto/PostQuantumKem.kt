package com.jelenxp.cryptochat.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * Post-kvantová výměna klíče na dálku přes ML-KEM-768 (FIPS 203, aktuální
 * NIST standard, nástupce dřívějšího návrhu Kyber). Na rozdíl od
 * Diffie-Hellman/RSA, které kvantový počítač se Shorovým algoritmem umí
 * rozlomit, je ML-KEM navržený tak, aby zůstal bezpečný i proti útoku
 * kvantovým počítačem.
 *
 * Žádný server není potřeba - celá výměna proběhne přes dva kousky textu
 * (Base64/QR), které si uživatelé pošlou libovolným kanálem (SMS, e-mail,
 * jiná appka...) - viz [com.jelenxp.cryptochat.ui.screens.RemoteInitScreen]
 * a [com.jelenxp.cryptochat.ui.screens.RemoteCompleteScreen].
 *
 * Postup:
 *  1) Iniciátor: [generateKeyPair] -> veřejný klíč pošle druhé straně.
 *  2) Druhá strana: [encapsulate] s přijatým veřejným klíčem -> získá
 *     odvozené klíče a "zapouzdření" (ciphertext), které pošle zpět.
 *  3) Iniciátor: [decapsulate] se svým soukromým klíčem a přijatým
 *     zapouzdřením -> odvodí STEJNÉ klíče jako v kroku 2.
 *
 * **Doménová separace (HKDF):** surové sdílené tajemství z ML-KEM se
 * nepoužívá přímo. Přes HKDF-SHA256 se z něj odvodí dvě NEZÁVISLÉ hodnoty,
 * každá s jiným „info" labelem:
 *  - 32bajtový AES-256-GCM klíč pro šifrování zpráv ([CryptoManager]),
 *  - krátký ověřovací kód SAS, který si obě strany potvrdí jiným kanálem
 *    jako obranu proti MITM.
 * SAS se čte nahlas (je veřejný), takže je důležité, že vznikl z jiného
 * HKDF výstupu než šifrovací klíč - jeho prozrazení tak o klíči nic neřekne.
 */
object PostQuantumKem {

    private val PARAMS = MLKEMParameters.ml_kem_768

    // HKDF „info" labely - doménová separace odvozených hodnot. Změna těchto
    // řetězců změní odvozené klíče, takže obě strany musí používat stejnou verzi.
    private const val INFO_AES_KEY = "CryptoChat/message-key/AES-256-GCM/v1"
    private const val INFO_SAS = "CryptoChat/verification-code/SAS/v1"
    private const val AES_KEY_BYTES = 32
    private const val SAS_BYTES = 6

    data class KeyPairBase64(val publicKeyBase64: String, val privateKeyBase64: String)

    /** Odvozené klíče z jedné výměny: šifrovací klíč (k uložení) + ověřovací kód (k potvrzení). */
    data class SharedKeys(val aesKeyBase64: String, val verificationCode: String)

    data class EncapsulationResult(val sharedKeys: SharedKeys, val encapsulationBase64: String)

    fun generateKeyPair(): KeyPairBase64 {
        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(SecureRandom(), PARAMS))
        val keyPair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as MLKEMPublicKeyParameters
        val privateKey = keyPair.private as MLKEMPrivateKeyParameters
        return KeyPairBase64(
            publicKeyBase64 = Base64Util.encode(publicKey.encoded),
            privateKeyBase64 = Base64Util.encode(privateKey.encoded)
        )
    }

    /** Strana, která PŘIJALA cizí veřejný klíč: vytvoří odvozené klíče + zapouzdření k odeslání zpět. */
    fun encapsulate(peerPublicKeyBase64: String): EncapsulationResult {
        val pubBytes = Base64Util.decode(peerPublicKeyBase64)
        val publicKey = MLKEMPublicKeyParameters(PARAMS, pubBytes)
        val kemGenerator = MLKEMGenerator(SecureRandom())
        val secretWithEncapsulation = kemGenerator.generateEncapsulated(publicKey)
        return EncapsulationResult(
            sharedKeys = deriveSharedKeys(secretWithEncapsulation.secret),
            encapsulationBase64 = Base64Util.encode(secretWithEncapsulation.encapsulation)
        )
    }

    /** Iniciátor: z vlastního soukromého klíče a přijatého zapouzdření odvodí stejné klíče. */
    fun decapsulate(privateKeyBase64: String, encapsulationBase64: String): SharedKeys {
        val privBytes = Base64Util.decode(privateKeyBase64)
        val encBytes = Base64Util.decode(encapsulationBase64)
        val privateKey = MLKEMPrivateKeyParameters(PARAMS, privBytes)
        val extractor = MLKEMExtractor(privateKey)
        val sharedSecret = extractor.extractSecret(encBytes)
        return deriveSharedKeys(sharedSecret)
    }

    /**
     * Ze surového sdíleného tajemství odvodí přes HKDF-SHA256 dvě nezávislé
     * hodnoty: AES klíč a ověřovací SAS kód. Každá má vlastní „info" label,
     * takže spolu kryptograficky nesouvisí.
     */
    private fun deriveSharedKeys(sharedSecret: ByteArray): SharedKeys {
        val aesKey = hkdf(sharedSecret, INFO_AES_KEY, AES_KEY_BYTES)
        val sasBytes = hkdf(sharedSecret, INFO_SAS, SAS_BYTES)
        return SharedKeys(
            aesKeyBase64 = Base64Util.encode(aesKey),
            verificationCode = formatSas(sasBytes)
        )
    }

    /** HKDF-SHA256 (RFC 5869), prázdný salt, doménový label v `info`. */
    private fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, null, info.toByteArray(Charsets.UTF_8)))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    /** Naformátuje bajty SAS na čitelný kód, např. „AB12 CD34 EF56". */
    private fun formatSas(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return hex.uppercase().chunked(4).joinToString(" ")
    }
}
