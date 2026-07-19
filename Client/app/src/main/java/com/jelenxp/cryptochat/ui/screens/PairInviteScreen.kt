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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
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
    val invite = remember { Pairing.generateInvite() }

    var phase by remember { mutableStateOf(if (baseUrl.isBlank()) InvitePhase.NO_RELAY else InvitePhase.WAITING) }
    var sas by remember { mutableStateOf("") }
    var aesKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    // Nahraje veřejný klíč do rendezvous schránky a čeká na odpověď protistrany.
    LaunchedEffect(Unit) {
        if (baseUrl.isBlank()) return@LaunchedEffect
        try {
            val keyPair = withContext(Dispatchers.IO) { PostQuantumKem.generateKeyPair() }
            val inviteKey = RelayCrypto.inviteKeyBase64(invite)
            val initBox = RelayCrypto.rendezvousId(invite, "init")
            val respBox = RelayCrypto.rendezvousId(invite, "resp")

            val uploaded = withContext(Dispatchers.IO) {
                RelayClient.put(baseUrl, initBox, Pairing.wrap(keyPair.publicKeyBase64, inviteKey))
            }
            if (!uploaded) { error = "Nepodařilo se spojit se serverem."; phase = InvitePhase.ERROR; return@LaunchedEffect }

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
            error = e.message ?: "Chyba při párování."
            phase = InvitePhase.ERROR
        }
    }

    CryptoScaffold(
        title = "Pozvánka pro $name",
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
                    InfoCard(text = "Nejdřív je potřeba nastavit adresu serveru chatu.")
                    Button(
                        onClick = { navController.navigate("relay_settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Nastavit server") }
                }

                InvitePhase.WAITING -> {
                    InfoCard(text = "Řekni tento kód druhému člověku. Jakmile ho zadá u sebe, spojení se " +
                        "naváže automaticky. Kód je jednorázový a brzy vyprší.")
                    CodeBox(display = Pairing.formatForDisplay(invite)) {
                        clipboard.setText(AnnotatedString(Pairing.formatForDisplay(invite)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Čekám, až se druhý připojí…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                InvitePhase.VERIFY -> {
                    VerifySas(
                        sas = sas,
                        onMatch = {
                            val id = contactId ?: UUID.randomUUID().toString()
                            viewModel.saveChatContact(id, name, aesKey, initiator = true)
                            navController.navigate("chat/$id") {
                                popUpTo("main") { inclusive = false }
                            }
                        },
                        onMismatch = { navController.popBackStack() }
                    )
                }

                InvitePhase.ERROR -> {
                    InfoCard(text = error.ifBlank { "Došlo k chybě." })
                    Button(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Zpět")
                    }
                }
            }
        }
    }
}

/** Velké čitelné zobrazení kódu s tlačítkem kopírovat. */
@Composable
private fun CodeBox(display: String, onCopy: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
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
                Icon(Icons.Default.ContentCopy, contentDescription = "Kopírovat")
            }
        }
    }
}

/** Potvrzení SAS kódu jako obrana proti MITM (server podstrčil svůj klíč). */
@Composable
private fun VerifySas(sas: String, onMatch: () -> Unit, onMismatch: () -> Unit) {
    Text("Ověřovací kód", style = MaterialTheme.typography.titleLarge)
    Text(
        "Porovnejte tento kód s druhou stranou (nahlas / jiným kanálem). Pokud se u obou " +
            "shoduje, spojení je bezpečné a nikdo se mezi vás nevloudil.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = sas,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        )
    }
    Button(onClick = onMatch, modifier = Modifier.fillMaxWidth()) { Text("Kódy se shodují — uložit") }
    OutlinedButton(onClick = onMismatch, modifier = Modifier.fillMaxWidth()) { Text("Neshodují se (zrušit)") }
}
