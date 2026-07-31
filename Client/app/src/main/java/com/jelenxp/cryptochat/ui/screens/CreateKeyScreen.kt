package com.jelenxp.cryptochat.ui.screens

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
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.ui.components.CopyableField
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.qr.QrCard
import com.jelenxp.cryptochat.ui.qr.generateQrBitmap
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

@Composable
fun CreateKeyScreen(
    name: String,
    navController: NavController,
    viewModel: ContactsViewModel,
    contactId: String? = null
) {
    val context = LocalContext.current

    val keyBase64 = remember { CryptoManager.keyToBase64(CryptoManager.generateKey()) }
    val qrBitmap = remember(keyBase64) { generateQrBitmap(keyBase64).asImageBitmap() }
    val copyLabel = stringResource(R.string.label_key_base64)

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
                // Klíč se zásadně sdílí QR kódem výše, do schránky se nekopíruje
                // (schránka je riziková - můžou ji číst jiné aplikace). Pole je
                // proto jen ke čtení, bez tlačítka kopírovat.
                onCopy = null
            )

            Button(
                onClick = {
                    val success = viewModel.saveExchangedKey(contactId, name, keyBase64, initiator = true)
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
