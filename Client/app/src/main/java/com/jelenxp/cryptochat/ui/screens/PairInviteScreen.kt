package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.Pairing
import com.jelenxp.cryptochat.chat.RelayClient
import com.jelenxp.cryptochat.chat.RelayCrypto
import com.jelenxp.cryptochat.crypto.PostQuantumKem
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class InvitePhase { NO_RELAY, WAITING, VERIFY, ERROR }

/**
 * Iniciátor online párování: vygeneruje jednorázový kód, ukáže ho a přes relay
 * počká, až se druhý připojí. Pak proběhne ML-KEM výměna, obě strany si potvrdí
 * SAS kód a kontakt se uloží (role initiator = true).
 */
@Composable
fun PairInviteScreen(
    name: String,
    navController: NavController,
    viewModel: ContactsViewModel,
    contactId: String? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings = remember { SettingsRepository(context) }
    val baseUrl = remember { settings.getRelayUrl() }

    // Jednorázová pozvánka (kanonická podoba pro výpočty, formát jen pro zobrazení).
    // MUSÍ přežít otočení telefonu - jinak by se vygeneroval nový kód do jiné
    // schránky a ten, který protistrana právě opisuje, by přestal platit.
    val invite = rememberSaveable { Pairing.generateInvite() }

    var phase by rememberSaveable { mutableStateOf(if (baseUrl.isBlank()) InvitePhase.NO_RELAY else InvitePhase.WAITING) }
    var sas by rememberSaveable { mutableStateOf("") }
    var aesKey by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    // Nahraje veřejný klíč do rendezvous schránky a čeká na odpověď protistrany.
    LaunchedEffect(Unit) {
        if (baseUrl.isBlank()) return@LaunchedEffect
        // Po otočení telefonu se efekt spustí znovu. Když už je klíč dohodnutý,
        // nesmí se protokol rozjet nanovo - přepsal by rendezvous schránku a
        // protistraně by ověření selhalo.
        if (aesKey.isNotEmpty()) return@LaunchedEffect
        try {
            val keyPair = withContext(Dispatchers.IO) { PostQuantumKem.generateKeyPair() }
            val inviteKey = RelayCrypto.inviteKeyBase64(invite)
            val initBox = RelayCrypto.rendezvousId(invite, "init")
            val respBox = RelayCrypto.rendezvousId(invite, "resp")

            val uploaded = withContext(Dispatchers.IO) {
                RelayClient.put(baseUrl, initBox, Pairing.wrap(keyPair.publicKeyBase64, inviteKey))
            }
            if (!uploaded) {
                error = context.getString(R.string.pair_error_connect)
                phase = InvitePhase.ERROR
                return@LaunchedEffect
            }

            // Poll odpovědi (zapouzdření od protistrany).
            while (isActive) {
                val encapsulation = withContext(Dispatchers.IO) {
                    try {
                        RelayClient.get(baseUrl, respBox).firstNotNullOfOrNull { Pairing.unwrap(it, inviteKey) }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (encapsulation != null) {
                    val keys = withContext(Dispatchers.IO) {
                        PostQuantumKem.decapsulate(keyPair.privateKeyBase64, encapsulation)
                    }
                    aesKey = keys.aesKeyBase64
                    sas = keys.verificationCode
                    phase = InvitePhase.VERIFY
                    break
                }
                delay(2500)
            }
        } catch (e: Exception) {
            error = context.getString(R.string.pair_error_generic)
            phase = InvitePhase.ERROR
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.pair_invite_title, name),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            when (phase) {
                InvitePhase.NO_RELAY -> {
                    InfoCard(text = stringResource(R.string.pair_need_server))
                    Button(
                        onClick = { navController.navigate("relay_settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_set_server)) }
                }

                InvitePhase.WAITING -> {
                    InfoCard(text = stringResource(R.string.pair_invite_instructions))
                    InviteCodeCard(display = Pairing.formatForDisplay(invite)) {
                        clipboard.setText(AnnotatedString(Pairing.formatForDisplay(invite)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.pair_invite_waiting), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                InvitePhase.VERIFY -> {
                    VerificationCodeContent(
                        verificationCode = sas,
                        contactName = name,
                        onConfirmed = {
                            val id = contactId ?: UUID.randomUUID().toString()
                            viewModel.saveChatContact(id, name, aesKey, initiator = true)
                            navController.navigate("chat/$id") {
                                popUpTo("main") { inclusive = false }
                            }
                        },
                        onCancel = { navController.popBackStack() }
                    )
                }

                InvitePhase.ERROR -> {
                    InfoCard(text = error.ifBlank { stringResource(R.string.pair_error_generic) })
                    Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_back))
                    }
                }
            }
        }
    }
}

/** Karta s jednorázovým pozvánkovým kódem: popisek, velký čitelný kód, kopírování. */
@Composable
private fun InviteCodeCard(display: String, onCopy: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = stringResource(R.string.pair_invite_code_caption).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = display,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy)
                    )
                }
            }
        }
    }
}
