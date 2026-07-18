package com.example.cryptochat.ui.screens

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.cryptochat.R
import com.example.cryptochat.crypto.PostQuantumKem
import com.example.cryptochat.data.Contact
import com.example.cryptochat.ui.components.CopyableField
import com.example.cryptochat.ui.components.CryptoScaffold
import com.example.cryptochat.ui.components.InfoCard
import com.example.cryptochat.ui.qr.QrCard
import com.example.cryptochat.ui.qr.buildQrScanOptions
import com.example.cryptochat.ui.qr.generateQrBitmap
import com.example.cryptochat.ui.util.LockPortraitWhileVisible
import com.example.cryptochat.ui.util.copyToClipboard
import com.example.cryptochat.viewmodel.ContactsViewModel
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class InitPhase { SHOW_PUBLIC_KEY, CONFIRM_CODE }

/**
 * Obrazovka pro stranu, která výměnu na dálku ZAHAJUJE (post-kvantový
 * ML-KEM handshake, viz PostQuantumKem.kt). Vygeneruje klíčový pár, ukáže
 * veřejný klíč k odeslání druhé straně, počká na její odpověď (vloženou
 * ručně nebo naskenovanou) a nakonec dopočítá sdílený klíč.
 */
@Composable
fun RemoteInitScreen(name: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current

    // Zámek na výšku: citlivý klíčový materiál níže se drží jen v paměti
    // (remember, ne rememberSaveable), aby se nezapsal do savedInstanceState.
    // Zamčení orientace zabrání tomu, aby rotace stav ztratila.
    LockPortraitWhileVisible()

    // Klíčový pár se generuje jednou. SOUKROMÝ klíč držíme jen v paměti
    // (remember) - nikdy nejde do savedInstanceState. Generování běží na
    // pozadí (Dispatchers.Default), ať ML-KEM keygen nezablokuje hlavní vlákno;
    // dokud není hotové, ukáže se indikátor načítání.
    var publicKeyBase64 by remember { mutableStateOf<String?>(null) }
    var privateKeyBase64 by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (publicKeyBase64 == null || privateKeyBase64 == null) {
            val kp = withContext(Dispatchers.Default) { PostQuantumKem.generateKeyPair() }
            publicKeyBase64 = kp.publicKeyBase64
            privateKeyBase64 = kp.privateKeyBase64
        }
    }

    var phase by remember { mutableStateOf(InitPhase.SHOW_PUBLIC_KEY) }
    var responseText by remember { mutableStateOf("") }
    var aesKeyBase64 by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val errorInvalidResponse = stringResource(R.string.error_invalid_remote_response)
    val errorSaveFailed = stringResource(R.string.error_save_failed)
    val scanPrompt = stringResource(R.string.scan_prompt)
    val copyToast = stringResource(R.string.toast_copied)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            responseText = result.contents
            error = null
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.title_remote_init, name),
        onBack = { navController.popBackStack() }
    ) { padding ->
        val pubKey = publicKeyBase64
        val privKey = privateKeyBase64

        if (pubKey == null || privKey == null) {
            // Klíčový pár se ještě generuje.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.remote_init_generating),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@CryptoScaffold
        }

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
                InitPhase.SHOW_PUBLIC_KEY -> {
                    val qrBitmap = remember(pubKey) { generateQrBitmap(pubKey).asImageBitmap() }

                    InfoCard(
                        text = stringResource(R.string.remote_init_instructions, name),
                        icon = Icons.Default.Info
                    )

                    QrCard {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = stringResource(R.string.qr_content_desc),
                            modifier = Modifier.size(220.dp)
                        )
                    }

                    CopyableField(
                        label = stringResource(R.string.label_public_key),
                        value = pubKey,
                        onCopy = { context.copyToClipboard("pubkey", pubKey, copyToast) },
                        minHeight = 120.dp
                    )

                    HorizontalDivider()

                    Text(
                        stringResource(R.string.remote_init_await_response, name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        value = responseText,
                        onValueChange = { responseText = it; error = null },
                        label = { Text(stringResource(R.string.label_remote_response)) },
                        isError = error != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Button(
                        onClick = {
                            try {
                                val keys = PostQuantumKem.decapsulate(privKey, responseText)
                                aesKeyBase64 = keys.aesKeyBase64
                                verificationCode = keys.verificationCode
                                error = null
                                phase = InitPhase.CONFIRM_CODE
                            } catch (e: Exception) {
                                error = errorInvalidResponse
                            }
                        },
                        enabled = responseText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_finish_exchange)) }
                }

                InitPhase.CONFIRM_CODE -> {
                    VerificationCodeContent(
                        verificationCode = verificationCode,
                        contactName = name,
                        onConfirmed = {
                            val success = viewModel.addOrUpdateContact(
                                Contact(id = UUID.randomUUID().toString(), name = name, keyBase64 = aesKeyBase64)
                            )
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
