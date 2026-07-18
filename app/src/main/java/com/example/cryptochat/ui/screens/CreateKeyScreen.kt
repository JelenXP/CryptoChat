package com.example.cryptochat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.cryptochat.R
import com.example.cryptochat.crypto.CryptoManager
import com.example.cryptochat.data.Contact
import com.example.cryptochat.data.SettingsRepository
import com.example.cryptochat.ui.components.CopyableField
import com.example.cryptochat.ui.components.CryptoScaffold
import com.example.cryptochat.ui.components.InfoCard
import com.example.cryptochat.ui.qr.QrCard
import com.example.cryptochat.ui.qr.generateQrBitmap
import com.example.cryptochat.ui.util.copyToClipboard
import com.example.cryptochat.viewmodel.ContactsViewModel
import java.util.UUID

@Composable
fun CreateKeyScreen(name: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current

    val keyBase64 = remember { CryptoManager.keyToBase64(CryptoManager.generateKey()) }
    val qrBitmap = remember(keyBase64) { generateQrBitmap(keyBase64).asImageBitmap() }
    val copyLabel = stringResource(R.string.label_key_base64)
    val toastCopied = stringResource(R.string.toast_key_copied)
    // Kopírování klíče do schránky je citlivé - povolené jen když si to
    // uživatel výslovně zapnul v nastavení (jinak se klíč sdílí QR kódem).
    val keyCopyAllowed = remember { SettingsRepository(context).isKeyCopyAllowed() }

    CryptoScaffold(
        title = stringResource(R.string.title_key_for, name),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InfoCard(
                text = stringResource(R.string.create_key_instructions, name),
                icon = Icons.Default.Info
            )

            QrCard {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = stringResource(R.string.qr_content_desc),
                    modifier = Modifier.size(240.dp)
                )
            }

            CopyableField(
                label = copyLabel,
                value = keyBase64,
                // Když je kopírování klíče vypnuté, pole je jen ke čtení (bez
                // tlačítka) - klíč se sdílí QR kódem výše. Když je povolené,
                // označíme kopii jako citlivou (skryje se ze schránkového
                // náhledu/historie na Androidu 13+).
                onCopy = if (keyCopyAllowed) {
                    { context.copyToClipboard("key", keyBase64, toastCopied, sensitive = true) }
                } else null
            )

            Button(
                onClick = {
                    val success = viewModel.addOrUpdateContact(
                        Contact(id = UUID.randomUUID().toString(), name = name, keyBase64 = keyBase64)
                    )
                    if (success) {
                        navController.popBackStack("main", inclusive = false)
                    } else {
                        Toast.makeText(context, context.getString(R.string.error_save_failed), Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.btn_continue)) }
        }
    }
}
