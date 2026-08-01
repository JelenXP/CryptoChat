package com.jelenxp.cryptochat.chat

import com.jelenxp.cryptochat.crypto.Base64Util
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identita člena skupiny — dva nezávislé páry klíčů:
 *
 *  - **Ed25519** ([signKeyPair]/[sign]/[verify]) — autenticita. Podepisuje zprávy,
 *    roster (admin), klíčové zprávy, leave. Bez podpisu by v modelu se sdíleným
 *    skupinovým klíčem (`GS` zná KAŽDÝ člen) mohl kterýkoli člen podvrhnout zprávu
 *    jako někdo jiný.
 *  - **ML-KEM-768** ([sealKeyPair]/[seal]/[unseal]) — důvěrnost při distribuci `GS`.
 *    Kdokoli umí zapečetit `GS` členovi na jeho veřejný seal klíč (KEM-DEM), takže
 *    obnova klíče nemusí jít přes admina (Tier 2). Post-kvantové kvůli konzistenci
 *    s [com.jelenxp.cryptochat.crypto.PostQuantumKem].
 *
 * **Doménová separace podpisů:** [sign]/[verify] berou `label` (typ objektu) a
 * podepisují `label || 0x00 || data`. Různé labely = různé prefixy → podpis zprávy
 * nejde vydávat za podpis rosteru (obojí dělá tentýž Ed25519 klíč admina).
 *
 * Čistě JVM (BouncyCastle low-level + `javax.crypto`), bez Androidu → testovatelné.
 */
object GroupIdentity {

    private val KEM_PARAMS = MLKEMParameters.ml_kem_768

    private const val INFO_SEAL = "CryptoChat/group/seal/v1"

    // AES-256-GCM pro DEM část sealu (shodné s CryptoManager).
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BYTES = 32

    // Oddělovač mezi doménovým labelem a daty v podepisovaném bufferu. Labely
    // neobsahují 0x00, takže dvojice (label, data) je jednoznačně rozlišitelná.
    private const val LABEL_SEPARATOR: Byte = 0

    /** Ed25519 pár (Base64, 32 B veřejný / 32 B soukromý). */
    data class SignKeyPair(val publicKeyBase64: String, val privateKeyBase64: String)

    /** ML-KEM-768 seal pár (Base64). Veřejný klíč je velký (~1184 B). */
    data class SealKeyPair(val publicKeyBase64: String, val privateKeyBase64: String)

    // --- Ed25519 podpisy ---

    fun generateSignKeyPair(): SignKeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val pub = pair.public as Ed25519PublicKeyParameters
        val priv = pair.private as Ed25519PrivateKeyParameters
        return SignKeyPair(
            publicKeyBase64 = Base64Util.encode(pub.encoded),
            privateKeyBase64 = Base64Util.encode(priv.encoded)
        )
    }

    /**
     * Podepíše `label || 0x00 || data` soukromým Ed25519 klíčem. Vrací Base64 podpis.
     * `label` je typ podepisovaného objektu (doménová separace).
     */
    fun sign(privateKeyBase64: String, label: String, data: ByteArray): String {
        val priv = Ed25519PrivateKeyParameters(Base64Util.decode(privateKeyBase64), 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        val msg = domainMessage(label, data)
        signer.update(msg, 0, msg.size)
        return Base64Util.encode(signer.generateSignature())
    }

    /**
     * Ověří podpis nad `label || 0x00 || data`. **Vrací false na jakoukoli chybu**
     * (špatný podpis, poškozený klíč, neplatný Base64) — nikdy nevyhazuje na
     * nepřátelský vstup, aby přijímací roura nespadla.
     */
    fun verify(publicKeyBase64: String, label: String, data: ByteArray, signatureBase64: String): Boolean {
        return try {
            val pub = Ed25519PublicKeyParameters(Base64Util.decode(publicKeyBase64), 0)
            val sig = Base64Util.decode(signatureBase64)
            val signer = Ed25519Signer()
            signer.init(false, pub)
            val msg = domainMessage(label, data)
            signer.update(msg, 0, msg.size)
            signer.verifySignature(sig)
        } catch (_: Exception) {
            false
        }
    }

    private fun domainMessage(label: String, data: ByteArray): ByteArray {
        val labelBytes = label.toByteArray(Charsets.UTF_8)
        val out = ByteArray(labelBytes.size + 1 + data.size)
        System.arraycopy(labelBytes, 0, out, 0, labelBytes.size)
        out[labelBytes.size] = LABEL_SEPARATOR
        System.arraycopy(data, 0, out, labelBytes.size + 1, data.size)
        return out
    }

    // --- ML-KEM seal (KEM-DEM) ---

    fun generateSealKeyPair(): SealKeyPair {
        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(SecureRandom(), KEM_PARAMS))
        val pair = generator.generateKeyPair()
        val pub = pair.public as MLKEMPublicKeyParameters
        val priv = pair.private as MLKEMPrivateKeyParameters
        return SealKeyPair(
            publicKeyBase64 = Base64Util.encode(pub.encoded),
            privateKeyBase64 = Base64Util.encode(priv.encoded)
        )
    }

    /**
     * Zapečetí `plaintext` (typicky `GS`) na veřejný seal klíč příjemce. KEM-DEM:
     * ML-KEM zapouzdří čerstvé tajemství, z něj HKDF odvodí AES klíč a ten
     * AES-256-GCM zašifruje data s náhodným IV. Výstup (Base64):
     * `[2B délka zapouzdření][zapouzdření][12B IV][GCM ciphertext+tag]`.
     * Vyhodí `IllegalArgumentException` na neplatný veřejný klíč (chyba volajícího).
     */
    fun seal(plaintext: ByteArray, recipientSealPublicKeyBase64: String): String {
        val pub = MLKEMPublicKeyParameters(KEM_PARAMS, Base64Util.decode(recipientSealPublicKeyBase64))
        val encapsulated = MLKEMGenerator(SecureRandom()).generateEncapsulated(pub)
        val key = hkdf(encapsulated.secret, INFO_SEAL, AES_KEY_BYTES)

        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)

        val enc = encapsulated.encapsulation
        require(enc.size <= 0xFFFF) { "Zapouzdření je nečekaně dlouhé." }
        val out = ByteArray(2 + enc.size + IV_BYTES + ct.size)
        out[0] = ((enc.size ushr 8) and 0xFF).toByte()
        out[1] = (enc.size and 0xFF).toByte()
        System.arraycopy(enc, 0, out, 2, enc.size)
        System.arraycopy(iv, 0, out, 2 + enc.size, IV_BYTES)
        System.arraycopy(ct, 0, out, 2 + enc.size + IV_BYTES, ct.size)
        return Base64Util.encode(out)
    }

    /**
     * Rozpečetí blob z [seal] vlastním soukromým seal klíčem. **Vrací null na
     * jakoukoli chybu** (cizí klíč, poškození, useknutí) — nepřátelský vstup nikdy
     * neshodí přijímací rouru.
     */
    fun unseal(sealedBase64: String, sealPrivateKeyBase64: String): ByteArray? {
        return try {
            val blob = Base64Util.decode(sealedBase64)
            if (blob.size < 2 + IV_BYTES) return null
            val encLen = ((blob[0].toInt() and 0xFF) shl 8) or (blob[1].toInt() and 0xFF)
            // Musí zbýt aspoň zapouzdření + IV + neprázdný GCM tag.
            if (blob.size < 2 + encLen + IV_BYTES + 1) return null
            val enc = blob.copyOfRange(2, 2 + encLen)
            val iv = blob.copyOfRange(2 + encLen, 2 + encLen + IV_BYTES)
            val ct = blob.copyOfRange(2 + encLen + IV_BYTES, blob.size)

            val priv = MLKEMPrivateKeyParameters(KEM_PARAMS, Base64Util.decode(sealPrivateKeyBase64))
            val secret = MLKEMExtractor(priv).extractSecret(enc)
            val key = hkdf(secret, INFO_SEAL, AES_KEY_BYTES)

            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (_: Exception) {
            null
        }
    }

    /** HKDF-SHA256 (RFC 5869), prázdný salt, doménový label v `info`. */
    private fun hkdf(ikm: ByteArray, info: String, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, null, info.toByteArray(Charsets.UTF_8)))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }
}
