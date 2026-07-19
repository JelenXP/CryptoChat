package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

private const val ENC_MIME = "application/octet-stream"

/** Zašifrovaný soubor (dočasný na disku) připravený k uložení/sdílení. */
private class EncryptedResult(val file: File, val fileName: String)

/**
 * Zašifrování libovolného souboru pro daný kontakt - **rámcově a streamově**
 * (viz [FileStreamCipher]), takže i velké soubory zvládne bez velké paměti.
 * Výstup se zapíše do dočasného souboru a jde uložit nebo sdílet jako přílohu.
 */
@Composable
fun SendFileScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
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
    var result by remember { mutableStateOf<EncryptedResult?>(null) }

    val errorEncrypt = stringResource(R.string.error_file_encrypt_failed)
    val errorTooLarge = stringResource(R.string.error_file_too_large)
    val toastSaved = stringResource(R.string.toast_file_saved)
    val errorSaveFailed = stringResource(R.string.error_file_save_failed)
    val shareChooser = stringResource(R.string.share_encrypted_file_chooser)

    // Kontrakty držíme stabilní (remember), ať se launchery při častém
    // překreslení (progress bar) zbytečně znovu neregistrují.
    val getContentContract = remember { ActivityResultContracts.GetContent() }
    val createDocContract = remember { ActivityResultContracts.CreateDocument(ENC_MIME) }

    val pickLauncher = rememberLauncherForActivityResult(getContentContract) { uri ->
        val sk = secretKey ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val name = FileTransfer.displayName(context, uri) ?: "soubor"
        val mime = FileTransfer.mimeType(context, uri)
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
            val temp = FileTransfer.newTempFile(context, "$name.enc")
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        temp.outputStream().use { out ->
                            FileStreamCipher.encrypt(input, out, sk, name, mime, maxBytes) { done ->
                                if (size != null && size > 0) {
                                    progress = (done.toFloat() / size).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                    true
                } catch (e: Exception) {
                    temp.delete()
                    false
                }
            }
            busy = false
            if (ok) result = EncryptedResult(temp, "$name.enc") else error = errorEncrypt
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
        title = stringResource(R.string.title_send_file_to, contact?.name ?: ""),
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

            InfoCard(text = stringResource(R.string.send_file_instructions), icon = Icons.Default.Info)

            Button(
                onClick = { pickLauncher.launch("*/*") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_pick_file))
            }

            if (busy) {
                ProgressRow(label = stringResource(R.string.file_encrypting), progress = progress, indeterminate = indeterminate)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { r ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.file_encrypted_ready), style = MaterialTheme.typography.titleMedium)
                        Text(
                            r.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(onClick = { saveLauncher.launch(r.fileName) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_save_file))
                        }
                        FilledTonalButton(
                            onClick = { FileTransfer.shareFile(context, r.file, ENC_MIME, shareChooser) },
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

/** Řádek s průběhem (určitý progress bar, nebo neurčitý když velikost neznáme). */
@Composable
internal fun ProgressRow(label: String, progress: Float, indeterminate: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (indeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
