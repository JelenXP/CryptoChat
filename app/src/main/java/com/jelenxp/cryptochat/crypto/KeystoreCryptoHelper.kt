package com.jelenxp.cryptochat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chrání data "at rest" (uložená na disku) pomocí AES klíče, který žije
 * v Android Keystore a nikdy neopouští zabezpečený hardware/OS zařízení
 * (na podporovaných zařízeních je vázaný na TEE/StrongBox). Aplikace se
 * k surovým bajtům tohoto klíče nikdy nedostane - jen ho použije skrze
 * systémové Cipher API.
 *
 * Používá se k zašifrování sdílených AES klíčů kontaktů předtím, než se
 * uloží do SharedPreferences - takže i kdyby někdo měl přístup k souborům
 * aplikace (root, ADB backup, forenzní nástroj), uvidí jen nečitelný
 * šifrový text vázaný na konkrétní zařízení.
 */
object KeystoreCryptoHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "crypto_chat_storage_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Klíč nejde exportovat ani použít bez zámku zařízení odemčeného
            // alespoň jednou od restartu (výchozí chování Keystore).
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Zašifruje text klíčem z Android Keystore. Výstup: Base64(IV || ciphertext || tag). */
    fun encryptForStorage(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    /**
     * Dešifruje text zašifrovaný funkcí [encryptForStorage]. Vrátí null,
     * pokud dešifrování selže (např. Keystore klíč byl mezitím zneplatněn -
     * v takovém případě je nutné kontakt znovu spárovat, ale aplikace
     * nespadne).
     */
    fun decryptFromStorage(payloadBase64: String): String? {
        return try {
            val combined = Base64.decode(payloadBase64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
            val cipherBytes = combined.copyOfRange(IV_SIZE_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
