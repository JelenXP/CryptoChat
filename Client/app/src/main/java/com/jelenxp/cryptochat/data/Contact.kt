package com.jelenxp.cryptochat.data

/**
 * Reprezentuje jednoho "uživatele" v aplikaci - tedy jednoho člověka,
 * se kterým si vyměňujeme šifrované zprávy.
 *
 * keyBase64 = sdílený AES-256 klíč zakódovaný v Base64. Dokud není nastaven,
 * uživatel ještě neprošel výměnou klíče (vytvoření/přijetí).
 *
 * avatarPath = cesta k profilové fotce v soukromém úložišti appky (files/avatars/…),
 * nebo null, když kontakt fotku nemá (zobrazí se pak iniciála). Není to citlivá
 * hodnota (jen lokální cesta), do zálohy se nepřenáší - fotky zůstávají na zařízení.
 *
 * initiator = role při online párování pozvánkou (chat přes relay). true = tento
 * telefon pozvánku vytvořil (iniciátor), false = pozvánku přijal, null = kontakt
 * vznikl jinak (osobní výměna) a nemá roli. Určuje, na kterou schránku se posílá
 * a z které se čte (viz [com.jelenxp.cryptochat.chat.RelaySync]).
 */
data class Contact(
    val id: String,
    val name: String,
    val keyBase64: String?,
    val avatarPath: String? = null,
    val initiator: Boolean? = null
)
