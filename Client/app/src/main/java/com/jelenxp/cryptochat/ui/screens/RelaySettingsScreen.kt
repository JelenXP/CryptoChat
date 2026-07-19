package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.chat.RelayClient
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nastavení adresy relaye (serveru „slepé schránky"). Prázdná adresa = chat přes
 * server je vypnutý, appka funguje dál jako offline. Vzhled drží styl appky
 * (CryptoScaffold + InfoCard).
 */
@Composable
fun RelaySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(settings.getRelayUrl()) }
    var testing by remember { mutableStateOf(false) }

    CryptoScaffold(
        title = "Server chatu",
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
            InfoCard(
                icon = Icons.Default.CloudQueue,
                text = "Adresa zero-knowledge relaye pro chat přes internet. Server přeposílá jen " +
                    "zašifrované zprávy — nezná jejich obsah ani to, kdo komu píše. Necháš-li " +
                    "pole prázdné, chat přes server je vypnutý a appka funguje jako offline.\n\n" +
                    "Příklad: http://192.168.1.10:8787 (v místní síti) nebo .onion adresa přes Tor."
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Adresa serveru") },
                placeholder = { Text("http://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    settings.setRelayUrl(url)
                    Toast.makeText(context, "Uloženo", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Uložit") }

            OutlinedButton(
                onClick = {
                    val target = url.trim()
                    if (target.isEmpty()) {
                        Toast.makeText(context, "Zadej nejdřív adresu", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    testing = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { RelayClient.health(target) }
                        testing = false
                        Toast.makeText(
                            context,
                            if (ok) "Server odpovídá ✓" else "Server neodpovídá",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Otestovat spojení")
            }
        }
    }
}
