package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BACKUP_FILENAME = "cryptochat-backup.ccb"

/**
 * Záloha kontaktů: šifrovaný export do souboru (chráněný heslem) a import
 * zpět. Soubor se vybírá systémovým dialogem (Storage Access Framework),
 * takže není potřeba žádné oprávnění k úložišti.
 */
@Composable
fun BackupScreen(navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    val scope = rememberCoroutineScope()

    // Heslo zadané pro export čeká, než uživatel vybere cílový soubor.
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    // Bajty vybraného souboru čekají, než uživatel zadá heslo k importu.
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val toastExportDone = stringResource(R.string.toast_export_done)
    val errorExportFailed = stringResource(R.string.error_export_failed)
    val errorImportFailed = stringResource(R.string.error_import_failed)
    val errorBackupDecrypt = stringResource(R.string.error_backup_decrypt)
    val toastImportDoneFmt = stringResource(R.string.toast_import_done)

    // Výběr cíle exportu → zapíšeme zašifrovanou zálohu.
    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Krypto (PBKDF2 + Keystore) i zápis běží mimo hlavní vlákno, ať UI netrhá.
            val ok = withContext(Dispatchers.Default) {
                try {
                    val blob = viewModel.exportBackup(password.toCharArray())
                    val stream = context.contentResolver.openOutputStream(uri)
                    if (stream != null) {
                        stream.use { it.write(blob) }
                        true
                    } else false
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(context, if (ok) toastExportDone else errorExportFailed, Toast.LENGTH_LONG).show()
        }
    }

    // Výběr souboru importu → načteme bajty a zeptáme se na heslo.
    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("null input stream")
            pendingImportBytes = bytes
            showImportDialog = true
        } catch (e: Exception) {
            Toast.makeText(context, errorImportFailed, Toast.LENGTH_LONG).show()
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.settings_backup_label),
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
            InfoCard(text = stringResource(R.string.backup_intro), icon = Icons.Default.Info)

            // Export
            ActionCard(
                icon = Icons.Default.Upload,
                title = stringResource(R.string.backup_export_title),
                description = stringResource(R.string.backup_export_desc),
                buttonLabel = stringResource(R.string.btn_export),
                enabled = contacts.isNotEmpty(),
                onClick = { showExportDialog = true }
            )

            // Import
            ActionCard(
                icon = Icons.Default.Download,
                title = stringResource(R.string.backup_import_title),
                description = stringResource(R.string.backup_import_desc),
                buttonLabel = stringResource(R.string.btn_import),
                enabled = true,
                onClick = { openDocLauncher.launch(arrayOf("*/*")) }
            )
        }
    }

    if (showExportDialog) {
        PasswordDialog(
            title = stringResource(R.string.dialog_export_password_title),
            confirmField = true,
            onConfirm = { password ->
                showExportDialog = false
                pendingExportPassword = password
                createDocLauncher.launch(BACKUP_FILENAME)
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showImportDialog) {
        PasswordDialog(
            title = stringResource(R.string.dialog_import_password_title),
            confirmField = false,
            onConfirm = { password ->
                showImportDialog = false
                val bytes = pendingImportBytes
                pendingImportBytes = null
                if (bytes == null) return@PasswordDialog
                scope.launch {
                    // -1 = chyba (nejčastěji špatné heslo / poškozený soubor - GCM).
                    val count = withContext(Dispatchers.Default) {
                        try {
                            viewModel.importBackup(bytes, password.toCharArray())
                        } catch (e: Exception) {
                            -1
                        }
                    }
                    if (count >= 0) {
                        Toast.makeText(context, String.format(toastImportDoneFmt, count), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, errorBackupDecrypt, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showImportDialog = false }
        )
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    buttonLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(buttonLabel)
            }
        }
    }
}

/**
 * Dialog na heslo. Při exportu ([confirmField] = true) je i pole pro potvrzení
 * hesla; potvrdit jde jen když se hesla shodují a nejsou prázdná.
 */
@Composable
private fun PasswordDialog(
    title: String,
    confirmField: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val matches = !confirmField || password == confirm
    val valid = password.isNotEmpty() && matches

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.label_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirmField) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text(stringResource(R.string.label_password_confirm)) },
                        singleLine = true,
                        isError = confirm.isNotEmpty() && !matches,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (confirm.isNotEmpty() && !matches) {
                        Text(
                            stringResource(R.string.error_passwords_dont_match),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    stringResource(R.string.backup_password_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = valid) {
                Text(stringResource(R.string.btn_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
