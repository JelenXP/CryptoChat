package com.jelenxp.cryptochat.crypto

/**
 * Šifrování dat **at rest** jako rozhraní - kvůli testovatelnosti.
 *
 * Ostrá implementace ([KeystoreStorageCrypto]) stojí na Android Keystore, který
 * v jednotkových testech není k dispozici. Dokud byl volaný napřímo, nešel
 * `ChatRepository` otestovat vůbec - a bydlela v něm celá řada chyb, které pak
 * musela najít až revize (dedup podle `wireId`, vzkříšení zrušené reakce,
 * přepsání historie po nepovedeném čtení).
 *
 * Testy si sem dosadí průhlednou implementaci a tím zpřístupní celý repozitář.
 */
interface StorageCrypto {

    /** Zašifruje text k uložení. Při chybě smí vyhodit výjimku. */
    fun encrypt(plainText: String): String

    /** Dešifruje uložený text. Vrací `null`, když to nejde - NIKDY nevyhazuje. */
    fun decrypt(payload: String): String?
}

/** Ostrá implementace: neexportovatelný klíč v Android Keystore. */
object KeystoreStorageCrypto : StorageCrypto {
    override fun encrypt(plainText: String): String =
        KeystoreCryptoHelper.encryptForStorage(plainText)

    override fun decrypt(payload: String): String? =
        KeystoreCryptoHelper.decryptFromStorage(payload)
}
