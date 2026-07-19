package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
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
import com.jelenxp.cryptochat.crypto.PostQuantumKem
import com.jelenxp.cryptochat.ui.components.CopyableField
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.qr.QrCard
import com.jelenxp.cryptochat.ui.qr.buildQrScanOptions
import com.jelenxp.cryptochat.ui.qr.generateQrBitmap
import com.jelenxp.cryptochat.ui.util.LockPortraitWhileVisible
import com.jelenxp.cryptochat.ui.util.copyToClipboard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import com.journeyapps.barcodescanner.ScanContract

private enum class CompletePhase { INPUT_PUBLIC_KEY, SHOW_RESPONSE, CONFIRM_CODE }

/**
 * Obrazovka pro stranu, která výměnu na dálku DOKONČUJE: vloží veřejný
 * klíč přijatý od iniciátora, appka spočítá sdílený klíč a "zapouzdření",
 * které je potřeba poslat zpátky.
 */
@Composable
fun RemoteCompleteScreen(
    name: String,
    navController: NavController,
    viewModel: ContactsViewModel,
    contactId: String? = null
) {
    val context = LocalContext.current

    // Zámek na výšku: citlivý klíčový materiál (odvozený sdílený klíč) se drží
    // jen v paměti (remember), ne v savedInstanceState - zamčení orientace
    // zabrání ztrátě stavu rotací. Viz LockPortraitWhileVisible.
    LockPortraitWhileVisible()

    var phase by remember { mutableStateOf(CompletePhase.INPUT_PUBLIC_KEY) }
    var publicKeyText by remember { mutableStateOf("") }
    var encapsulationBase64 by remember { mutableStateOf("") }
    var aesKeyBase64 by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val errorInvalidPublicKey = stringResource(R.string.error_invalid_remote_public_key)
    val errorSaveFailed = stringResource(R.string.error_save_failed)
    val scanPrompt = stringResource(R.string.scan_prompt)
    val copyToast = stringResource(R.string.toast_copied)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            publicKeyText = result.contents
            error = null
        }
    }

    val qrBitmap = remember(encapsulationBase64) {
        if (encapsulationBase64.isNotBlank()) generateQrBitmap(encapsulationBase64).asImageBitmap() else null
    }

    CryptoScaffold(
        title = stringResource(R.string.title_remote_complete, name),
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
            when (phase) {
                CompletePhase.INPUT_PUBLIC_KEY -> {
                    InfoCard(
                        text = stringResource(R.string.remote_complete_instructions, name),
                        icon = Icons.Default.Info
                    )

                    Button(
                        onClick = { scanLauncher.launch(buildQrScanOptions(scanPrompt)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_scan_qr))
                    }

                    OutlinedTextField(
                        value = publicKeyText,
                        onValueChange = { publicKeyText = it; error = null },
                        label = { Text(stringResource(R.string.label_public_key)) },
                        isError = error != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Button(
                        onClick = {
                            try {
                                val result = PostQuantumKem.encapsulate(publicKeyText)
                                aesKeyBase64 = result.sharedKeys.aesKeyBase64
                                verificationCode = result.sharedKeys.verificationCode
                                encapsulationBase64 = result.encapsulationBase64
                                error = null
                                phase = CompletePhase.SHOW_RESPONSE
                            } catch (e: Exception) {
                                error = errorInvalidPublicKey
                            }
                        },
                        enabled = publicKeyText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_process_key)) }
                }

                CompletePhase.SHOW_RESPONSE -> {
                    InfoCard(
                        text = stringResource(R.string.remote_complete_send_response, name),
                        icon = Icons.Default.Info
                    )

                    qrBitmap?.let {
                        QrCard {
                            Image(
                                bitmap = it,
                                contentDescription = stringResource(R.string.qr_content_desc),
                                modifier = Modifier.size(220.dp)
                            )
                        }
                    }

                    CopyableField(
                        label = stringResource(R.string.label_remote_response),
                        value = encapsulationBase64,
                        onCopy = { context.copyToClipboard("response", encapsulationBase64, copyToast) },
                        minHeight = 120.dp
                    )

                    Button(
                        onClick = { phase = CompletePhase.CONFIRM_CODE },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_continue)) }
                }

                CompletePhase.CONFIRM_CODE -> {
                    VerificationCodeContent(
                        verificationCode = verificationCode,
                        contactName = name,
                        onConfirmed = {
                            val success = viewModel.saveExchangedKey(contactId, name, aesKeyBase64)
                            if (success) {
                                navController.popBackStack("main", inclusive = false)
                            } else {
                                Toast.makeText(context, errorSaveFailed, Toast.LENGTH_LONG).show()
                            }
                        },
                        onCancel = { navController.popBackStack("main", inclusive = false) }
                    )
                }
            }
        }
    }
}
