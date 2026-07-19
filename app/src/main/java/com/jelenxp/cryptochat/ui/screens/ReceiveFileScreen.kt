package com.jelenxp.cryptochat.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.crypto.FileStreamCipher
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.util.FileTransfer
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val FALLBACK_MIME = "application/octet-stream"
private const val PREVIEW_MAX_PX = 1280

/** Dešifrovaný soubor (dočasný na disku) + metadata a volitelný náhled obrázku. */
private class DecryptedResult(
    val file: File,
    val name: String,
    val mime: String?,
    val preview: ImageBitmap?
)

/**
 * Dešifrování souboru přijatého od kontaktu - **rámcově a streamově**. Výstup
 * jde do dočasného souboru, obnoví se původní jméno i MIME; jde-li o obrázek,
 * ukáže se náhled. Výsledek lze uložit nebo sdílet.
 */
@Composable
fun ReceiveFileScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contact = viewModel.getContact(id)
    val key = contact?.keyBase64
    val secretKey = remember(key) { key?.let { runCatching { CryptoManager.keyFromBase64(it) }.getOrNull() } }
    val settings = remember { SettingsRepository(context) }

    LaunchedEffect(Unit) { FileTransfer.clearTemp(context) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var indeterminate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DecryptedResult?>(null) }

    val errorDecrypt = stringResource(R.string.error_file_decrypt_failed)
    val errorTooLarge = stringResource(R.string.error_file_too_large)
    val toastSaved = stringResource(R.string.toast_file_saved)
    val errorSaveFailed = stringResource(R.string.error_file_save_failed)
    val shareChooser = stringResource(R.string.share_decrypted_file_chooser)

    // Stabilní kontrakty (remember), ať se launchery při progress překreslování
    // zbytečně znovu neregistrují. Cíl uložení má MIME podle dešifrovaného souboru.
    val getContentContract = remember { ActivityResultContracts.GetContent() }
    val createDocContract = remember(result?.mime) {
        ActivityResultContracts.CreateDocument(result?.mime ?: FALLBACK_MIME)
    }

    val pickLauncher = rememberLauncherForActivityResult(getContentContract) { uri ->
        val sk = secretKey ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val size = FileTransfer.fileSize(context, uri)
        val maxBytes = if (settings.isFileSizeLimitEnabled()) FileTransfer.DEFAULT_LIMIT_BYTES else Long.MAX_VALUE

        if (size != null && size > maxBytes) {
            error = errorTooLarge
            result = null
            return@rememberLauncherForActivityResult
        }

        error = null
        result = null
        progress = 0f
        indeterminate = (size == null || size <= 0)
        busy = true
        scope.launch {
            val temp = FileTransfer.newTempFile(context, "cryptochat_decrypted.tmp")
            val dec = withContext(Dispatchers.IO) {
                try {
                    val meta = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        temp.outputStream().use { out ->
                            FileStreamCipher.decrypt(input, out, sk, maxBytes) { done ->
                                if (size != null && size > 0) {
                                    progress = (done.toFloat() / size).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                    val preview = if (isImage(meta.mime, meta.name)) {
                        decodePreview(temp)?.asImageBitmap()
                    } else null
                    DecryptedResult(temp, meta.name, meta.mime, preview)
                } catch (e: Exception) {
                    temp.delete()
                    null
                }
            }
            busy = false
            if (dec != null) result = dec else error = errorDecrypt
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(createDocContract) { uri ->
        val r = result
        if (uri == null || r == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { FileTransfer.copyToUri(context, r.file, uri) }
            Toast.makeText(context, if (ok) toastSaved else errorSaveFailed, Toast.LENGTH_LONG).show()
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.title_receive_file_from, contact?.name ?: ""),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (secretKey == null) {
                InfoCard(text = stringResource(R.string.user_no_key))
                return@Column
            }

            InfoCard(text = stringResource(R.string.receive_file_instructions), icon = Icons.Default.Info)

            Button(
                onClick = { pickLauncher.launch("*/*") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_pick_encrypted_file))
            }

            if (busy) {
                ProgressRow(label = stringResource(R.string.file_decrypting), progress = progress, indeterminate = indeterminate)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { r ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.file_decrypted_ready), style = MaterialTheme.typography.titleMedium)
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        r.preview?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            )
                        }
                        Button(onClick = { saveLauncher.launch(r.name) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_save_file))
                        }
                        FilledTonalButton(
                            onClick = { FileTransfer.shareFile(context, r.file, r.mime ?: FALLBACK_MIME, shareChooser) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_share_file))
                        }
                    }
                }
            }
        }
    }
}

/** Je to obrázek? Podle MIME nebo přípony jména. */
private fun isImage(mime: String?, name: String): Boolean {
    if (mime != null && mime.startsWith("image/")) return true
    val lower = name.lowercase()
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { lower.endsWith(it) }
}

/** Dekóduje zmenšený náhled obrázku z dočasného souboru (nebo null). */
private fun decodePreview(file: File): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= PREVIEW_MAX_PX && h / 2 >= PREVIEW_MAX_PX) {
            w /= 2; h /= 2; sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    } catch (e: Exception) {
        null
    }
}
