package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.Pairing
import com.jelenxp.cryptochat.chat.RelayClient
import com.jelenxp.cryptochat.chat.RelayCrypto
import com.jelenxp.cryptochat.crypto.PostQuantumKem
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.util.LockPortraitWhileVisible
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
    // Zamkne orientaci po dobu párování. Bez toho by otočení ve fázi WORKING
    // zrušilo korutinu z rememberCoroutineScope, ale phase (rememberSaveable) by
    // zůstal WORKING -> zatuhlý spinner bez restartu. Stejně jako PairInviteScreen.
    LockPortraitWhileVisible()

    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val baseUrl = remember { settings.getRelayUrl() }
    val scope = rememberCoroutineScope()

    // rememberSaveable: otočení telefonu ve fázi ověřování by jinak zahodilo
    // dohodnutý klíč, ačkoli zapouzdření už je nahrané na relayi - iniciátor by
    // kontakt uložil, tahle strana ne (jednostranné, na pohled záhadné párování).
    var phase by rememberSaveable { mutableStateOf(if (baseUrl.isBlank()) JoinPhase.NO_RELAY else JoinPhase.INPUT) }
    var code by rememberSaveable { mutableStateOf("") }
    var sas by rememberSaveable { mutableStateOf("") }
    var aesKey by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    fun startJoin() {
        val canonical = Pairing.normalize(code)
        if (!Pairing.looksValid(canonical)) {
            error = context.getString(R.string.pair_error_bad_code)
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
                            RelayClient.get(baseUrl, initBox).blobs.firstNotNullOfOrNull { Pairing.unwrap(it, inviteKey) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (peerPublicKey == null) { attempts++; delay(2500) }
                }
                if (peerPublicKey == null) {
                    error = context.getString(R.string.pair_error_not_found)
                    phase = JoinPhase.ERROR
                    return@launch
                }

                val result = withContext(Dispatchers.IO) { PostQuantumKem.encapsulate(peerPublicKey) }
                val sent = withContext(Dispatchers.IO) {
                    RelayClient.put(baseUrl, respBox, Pairing.wrap(result.encapsulationBase64, inviteKey))
                }
                if (!sent) {
                    error = context.getString(R.string.pair_error_send)
                    phase = JoinPhase.ERROR
                    return@launch
                }

                aesKey = result.sharedKeys.aesKeyBase64
                sas = result.sharedKeys.verificationCode
                phase = JoinPhase.VERIFY
            } catch (e: Exception) {
                error = context.getString(R.string.pair_error_generic)
                phase = JoinPhase.ERROR
            }
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.pair_join_title, name),
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
                    InfoCard(text = stringResource(R.string.pair_need_server))
                    Button(
                        onClick = { navController.navigate("relay_settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_set_server)) }
                }

                JoinPhase.INPUT -> {
                    InfoCard(text = stringResource(R.string.pair_join_instructions))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.pair_code_label)) },
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
                    ) { Text(stringResource(R.string.btn_join)) }
                }

                JoinPhase.WORKING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.pair_working), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                JoinPhase.VERIFY -> {
                    VerificationCodeContent(
                        verificationCode = sas,
                        contactName = name,
                        onConfirmed = {
                            val id = contactId ?: UUID.randomUUID().toString()
                            viewModel.saveChatContact(id, name, aesKey, initiator = false)
                            navController.navigate("chat/$id") {
                                popUpTo("main") { inclusive = false }
                            }
                        },
                        onCancel = { navController.popBackStack() }
                    )
                }

                JoinPhase.ERROR -> {
                    InfoCard(text = error.ifBlank { stringResource(R.string.pair_error_generic) })
                    Button(onClick = { phase = JoinPhase.INPUT }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_retry))
                    }
                }
            }
        }
    }
}
