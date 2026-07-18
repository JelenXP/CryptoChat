package com.example.cryptochat.ui.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.example.cryptochat.R

/**
 * Zkopíruje text do schránky. Dřív byl tenhle blok nakopírovaný v každé
 * obrazovce zvlášť - teď je na jednom místě a odolný proti chybějící službě
 * schránky (nikdy nespadne).
 *
 * @param toastMessage pokud není null, zobrazí se po zkopírování krátký toast.
 * @param sensitive pokud true, označí obsah jako citlivý - na Androidu 13+ ho
 *   systém neukáže v náhledu schránky ani v historii. Používej pro tajné klíče.
 */
fun Context.copyToClipboard(
    label: String,
    text: String,
    toastMessage: String? = null,
    sensitive: Boolean = false
) {
    try {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    } catch (e: Exception) {
        Toast.makeText(this, getString(R.string.error_copy_failed), Toast.LENGTH_SHORT).show()
    }
}
