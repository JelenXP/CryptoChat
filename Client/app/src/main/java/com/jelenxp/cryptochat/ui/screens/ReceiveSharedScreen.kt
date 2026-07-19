package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.util.copyToClipboard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

/**
 * Cíl pro „Sdílet do CryptoChat": dostane text sdílený z jiné aplikace a nabídne
 * jeho dešifrování. Protože z textu samotného nepoznáme odesílatele, uživatel
 * vybere kontakt, jehož klíčem se má zpráva rozšifrovat. GCM ověří integritu -
 * špatný klíč / poškozený text skončí chybou (a jde zkusit jiný kontakt).
 */
@Composable
fun ReceiveSharedScreen(
    cipherText: String,
    navController: NavController,
    viewModel: ContactsViewModel
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    val withKey = remember(contacts) { contacts.filter { it.keyBase64 != null } }

    var decrypted by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val errorDecryptFailed = stringResource(R.string.error_decrypt_failed)
    val copyToast = stringResource(R.string.toast_copied)

    CryptoScaffold(
        title = stringResource(R.string.receive_shared_title),
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
            when {
                cipherText.isBlank() ->
                    InfoCard(text = stringResource(R.string.receive_shared_empty))

                withKey.isEmpty() ->
                    InfoCard(text = stringResource(R.string.receive_shared_no_contacts))

                else -> {
                    InfoCard(text = stringResource(R.string.receive_shared_instructions))

                    // Náhled přijatého (zašifrovaného) textu.
                    OutlinedTextField(
                        value = cipherText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.label_encrypted_message)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 160.dp)
                    )

                    if (decrypted == null) {
                        Text(
                            text = stringResource(R.string.receive_shared_pick),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        withKey.forEach { contact ->
                            AppCard(
                                onClick = {
                                    try {
                                        val secretKey = CryptoManager.keyFromBase64(contact.keyBase64!!)
                                        decrypted = CryptoManager.decrypt(cipherText, secretKey)
                                        error = null
                                    } catch (e: Exception) {
                                        error = errorDecryptFailed
                                        decrypted = null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    ContactAvatar(
                                        name = contact.name,
                                        avatarPath = contact.avatarPath,
                                        size = 40.dp
                                    )
                                    Text(
                                        text = contact.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }

                    decrypted?.let {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.label_original_message),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        context.copyToClipboard("message", it, copyToast)
                                    }) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = stringResource(R.string.content_desc_copy)
                                        )
                                    }
                                }
                                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
