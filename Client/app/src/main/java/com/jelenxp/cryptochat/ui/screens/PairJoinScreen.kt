package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class JoinPhase { NO_RELAY, INPUT, WORKING, VERIFY, ERROR }

/**
 * Odpovídající strana online párování: zadá kód od iniciátora, přes relay si
 * vyzvedne jeho veřejný klíč, provede ML-KEM zapouzdření a pošle odpověď zpět.
 * Po potvrzení SAS kódu se kontakt uloží (role initiator = false).
 */
@Composable
fun PairJoinScreen(
    name: String,
    navController: NavController,
    viewModel: ContactsViewModel,
    contactId: String? = null
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val baseUrl = remember { settings.getRelayUrl() }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(if (baseUrl.isBlank()) JoinPhase.NO_RELAY else JoinPhase.INPUT) }
    var code by remember { mutableStateOf("") }
    var sas by remember { mutableStateOf("") }
    var aesKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun startJoin() {
        val canonical = Pairing.normalize(code)
        if (!Pairing.looksValid(canonical)) {
            error = "Kód nemá správný tvar."
            return
        }
        error = ""
        phase = JoinPhase.WORKING
        scope.launch {
            try {
                val inviteKey = RelayCrypto.inviteKeyBase64(canonical)
                val initBox = RelayCrypto.rendezvousId(canonical, "init")
                val respBox = RelayCrypto.rendezvousId(canonical, "resp")

                // Vyzvedni veřejný klíč iniciátora (pár pokusů, kdyby ještě nenahrál).
                var peerPublicKey: String? = null
                var attempts = 0
                while (peerPublicKey == null && attempts < 12) {
                    peerPublicKey = withContext(Dispatchers.IO) {
                        try {
                            RelayClient.get(baseUrl, initBox).firstNotNullOfOrNull { Pairing.unwrap(it, inviteKey) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (peerPublicKey == null) { attempts++; delay(2500) }
                }
                if (peerPublicKey == null) {
                    error = "Pozvánka nenalezena nebo vypršela. Zkontroluj kód a zkus to znovu."
                    phase = JoinPhase.ERROR
                    return@launch
                }

                val result = withContext(Dispatchers.IO) { PostQuantumKem.encapsulate(peerPublicKey) }
                val sent = withContext(Dispatchers.IO) {
                    RelayClient.put(baseUrl, respBox, Pairing.wrap(result.encapsulationBase64, inviteKey))
                }
                if (!sent) { error = "Nepodařilo se odeslat odpověď serveru."; phase = JoinPhase.ERROR; return@launch }

                aesKey = result.sharedKeys.aesKeyBase64
                sas = result.sharedKeys.verificationCode
                phase = JoinPhase.VERIFY
            } catch (e: Exception) {
                error = e.message ?: "Chyba při párování."
                phase = JoinPhase.ERROR
            }
        }
    }

    CryptoScaffold(
        title = "Pozvánka od $name",
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
                JoinPhase.NO_RELAY -> {
                    InfoCard(text = "Nejdřív je potřeba nastavit adresu serveru chatu.")
                    Button(
                        onClick = { navController.navigate("relay_settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Nastavit server") }
                }

                JoinPhase.INPUT -> {
                    InfoCard(text = "Zadej kód, který ti řekl druhý člověk. Spojení se pak naváže automaticky.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Pozvánkový kód") },
                        placeholder = { Text("ABCD-EFGH-JKMN-PQRS") },
                        singleLine = true,
                        isError = error.isNotBlank(),
                        supportingText = { if (error.isNotBlank()) Text(error) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { startJoin() },
                        enabled = code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Připojit se") }
                }

                JoinPhase.WORKING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Navazuji spojení…", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                JoinPhase.VERIFY -> {
                    Text("Ověřovací kód", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Porovnejte tento kód s druhou stranou. Pokud se u obou shoduje, spojení je " +
                            "bezpečné a nikdo se mezi vás nevloudil.",
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
                    Button(
                        onClick = {
                            val id = contactId ?: UUID.randomUUID().toString()
                            viewModel.saveChatContact(id, name, aesKey, initiator = false)
                            navController.navigate("chat/$id") {
                                popUpTo("main") { inclusive = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Kódy se shodují — uložit") }
                    OutlinedButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Neshodují se (zrušit)") }
                }

                JoinPhase.ERROR -> {
                    InfoCard(text = error.ifBlank { "Došlo k chybě." })
                    Button(onClick = { phase = JoinPhase.INPUT }, modifier = Modifier.fillMaxWidth()) {
                        Text("Zkusit znovu")
                    }
                }
            }
        }
    }
}
