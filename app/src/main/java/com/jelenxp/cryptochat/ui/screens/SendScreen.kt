package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.ui.components.CopyableField
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.util.copyToClipboard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

@Composable
fun SendScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val contact = viewModel.getContact(id)

    var message by remember { mutableStateOf("") }
    var encrypted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val errorEncryptFailed = stringResource(R.string.error_encrypt_failed)
    val copyToast = stringResource(R.string.toast_copied)
    val title = stringResource(R.string.title_send_to, contact?.name ?: "")
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
                // Bez klíče nejde šifrovat - kontakt buď neexistuje, nebo
                // ještě neproběhla výměna klíče.
                InfoCard(text = stringResource(R.string.user_no_key))
                return@Column
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it; error = null },
                label = { Text(stringResource(R.string.label_message)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            Button(
                onClick = {
                    try {
                        val secretKey = CryptoManager.keyFromBase64(key)
                        encrypted = CryptoManager.encrypt(message, secretKey)
                        error = null
                    } catch (e: Exception) {
                        error = errorEncryptFailed
                        encrypted = ""
                    }
                },
                enabled = message.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_encrypt))
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (encrypted.isNotBlank()) {
                CopyableField(
                    label = stringResource(R.string.label_encrypted_message),
                    value = encrypted,
                    onCopy = { context.copyToClipboard("encrypted", encrypted, copyToast) },
                    minHeight = 150.dp
                )
            }
        }
    }
}
