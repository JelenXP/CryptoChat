package com.example.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.cryptochat.R
import com.example.cryptochat.crypto.CryptoManager
import com.example.cryptochat.ui.components.AppCard
import com.example.cryptochat.ui.components.CryptoScaffold
import com.example.cryptochat.ui.components.InfoCard
import com.example.cryptochat.ui.util.copyToClipboard
import com.example.cryptochat.viewmodel.ContactsViewModel

@Composable
fun ReceiveScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val contact = viewModel.getContact(id)

    var cipherText by remember { mutableStateOf("") }
    var decrypted by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val errorDecryptFailed = stringResource(R.string.error_decrypt_failed)
    val title = stringResource(R.string.title_receive_from, contact?.name ?: "")
    val copyToast = stringResource(R.string.toast_copied)
    val key = contact?.keyBase64

    CryptoScaffold(title = title, onBack = { navController.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (key == null) {
                InfoCard(text = stringResource(R.string.user_no_key))
                return@Column
            }

            OutlinedTextField(
                value = cipherText,
                onValueChange = { cipherText = it; decrypted = null; error = null },
                label = { Text(stringResource(R.string.label_encrypted_message)) },
                isError = error != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )

            Button(
                onClick = {
                    try {
                        val secretKey = CryptoManager.keyFromBase64(key)
                        decrypted = CryptoManager.decrypt(cipherText, secretKey)
                        error = null
                    } catch (e: Exception) {
                        error = errorDecryptFailed
                        decrypted = null
                    }
                },
                enabled = cipherText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_decrypt))
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

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
                            // Dešifrovaná zpráva není citlivá klíčovina - kopírování
                            // povolené vždy (bez ohledu na nastavení kopírování klíče).
                            IconButton(onClick = {
                                context.copyToClipboard("message", it, copyToast)
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.content_desc_copy)
                                )
                            }
                        }
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
