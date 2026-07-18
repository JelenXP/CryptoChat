package com.example.cryptochat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.cryptochat.R
import com.example.cryptochat.crypto.Base64Util
import com.example.cryptochat.data.Contact
import com.example.cryptochat.ui.components.CryptoScaffold
import com.example.cryptochat.ui.components.InfoCard
import com.example.cryptochat.ui.qr.buildQrScanOptions
import com.example.cryptochat.viewmodel.ContactsViewModel
import com.journeyapps.barcodescanner.ScanContract
import java.util.UUID

@Composable
fun AcceptKeyScreen(name: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    var keyText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val errorInvalidLength = stringResource(R.string.error_invalid_key_length)
    val errorInvalidFormat = stringResource(R.string.error_invalid_key_format)
    val errorSaveFailed = stringResource(R.string.error_save_failed)
    val errorCameraPermission = stringResource(R.string.error_camera_permission)
    val scanPrompt = stringResource(R.string.scan_prompt)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // result.contents je null i v případě, že uživatel sken zrušil -
        // to není chyba, jen se nic nestane.
        if (result.contents != null) {
            keyText = result.contents
            error = null
        }
    }

    // Explicitní žádost o oprávnění ke kameře PŘED spuštěním skeneru -
    // předchází možnému pádu (SecurityException), pokud by oprávnění chybělo.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(buildQrScanOptions(scanPrompt))
        } else {
            error = errorCameraPermission
        }
    }

    fun startScan() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            scanLauncher.launch(buildQrScanOptions(scanPrompt))
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.title_accept_key_from, name),
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
            InfoCard(
                text = stringResource(R.string.accept_key_instructions, name),
                icon = Icons.Default.Info
            )

            Button(
                onClick = { startScan() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_scan_qr))
            }

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it; error = null },
                label = { Text(stringResource(R.string.label_key_base64)) },
                isError = error != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    try {
                        val decoded = Base64Util.decode(keyText)
                        if (decoded.size != 32) {
                            error = errorInvalidLength
                            return@Button
                        }
                        val success = viewModel.addOrUpdateContact(
                            Contact(id = UUID.randomUUID().toString(), name = name, keyBase64 = keyText.trim())
                        )
                        if (success) {
                            navController.popBackStack("main", inclusive = false)
                        } else {
                            Toast.makeText(context, errorSaveFailed, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        error = errorInvalidFormat
                    }
                },
                enabled = keyText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.btn_continue)) }
        }
    }
}
