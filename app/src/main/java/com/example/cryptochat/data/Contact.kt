package com.example.cryptochat.data

/**
 * Reprezentuje jednoho "uživatele" v aplikaci - tedy jednoho člověka,
 * se kterým si vyměňujeme šifrované zprávy.
 *
 * keyBase64 = sdílený AES-256 klíč zakódovaný v Base64. Dokud není nastaven,
 * uživatel ještě neprošel výměnou klíče (vytvoření/přijetí).
 */
data class Contact(
    val id: String,
    val name: String,
    val keyBase64: String?
)
