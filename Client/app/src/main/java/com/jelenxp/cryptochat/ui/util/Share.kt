package com.jelenxp.cryptochat.ui.util

import android.content.Context
import android.content.Intent

/**
 * Odeslání textu přes systémové „Sdílet" (ACTION_SEND). Uživatel si v systémovém
 * dialogu vybere cílovou appku (SMS, e-mail, jiný messenger…) - žádná síť ani
 * data neprochází přes CryptoChat, jen se předá hotový (zašifrovaný) text.
 */
fun Context.shareText(text: String, chooserTitle: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, chooserTitle))
    } catch (e: Exception) {
        // Bez appky schopné sdílet text - tiše ignorovat (appka nespadne).
    }
}
