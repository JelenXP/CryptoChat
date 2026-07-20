package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.diagnostics.BugReporter
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.theme.LocalUiSpacing
import com.jelenxp.cryptochat.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stav odesílání hlášení (řídí tlačítko i výsledný dialog). */
private sealed interface SendState {
    data object Idle : SendState
    data object Sending : SendState
    data class Done(val result: BugReporter.Result) : SendState
}

/**
 * Obrazovka „Nahlásit chybu". Uživatel napíše, co se pokazilo, zaškrtne, co se
 * má přiložit, a **může si předem přečíst přesně to, co se odešle**. Odeslání je
 * čistě dobrovolné a jde přes Tor (viz [BugReporter]).
 */
@Composable
fun BugReportScreen(navController: NavController) {
    val context = LocalContext.current
    val spacing = LocalUiSpacing.current
    val scope = rememberCoroutineScope()

    var description by rememberSaveable { mutableStateOf("") }
    var attachDevice by rememberSaveable { mutableStateOf(true) }
    var attachDiagnostics by rememberSaveable { mutableStateOf(true) }
    var attachConnection by rememberSaveable { mutableStateOf(true) }
    // Poslední volba je záměrně vypnutá - záznam o pádu je nejcitlivější příloha
    // (stacktrace může obsahovat i názvy tříd z cest), takže ji musí zapnout
    // uživatel vědomě.
    var attachCrash by rememberSaveable { mutableStateOf(false) }

    val crashAvailable = remember { BugReporter.hasCrashLog(context) }
    var sendState by remember { mutableStateOf<SendState>(SendState.Idle) }
    var preview by remember { mutableStateOf<String?>(null) }

    val options = BugReporter.Options(
        device = attachDevice,
        diagnostics = attachDiagnostics,
        connection = attachConnection,
        crash = attachCrash && crashAvailable
    )
    val canSend = description.isNotBlank() && sendState != SendState.Sending

    CryptoScaffold(
        title = stringResource(R.string.bug_report_title),
        onBack = { navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenPad),
            verticalArrangement = Arrangement.spacedBy(spacing.itemGap)
        ) {
            InfoCard(
                text = stringResource(R.string.bug_report_intro),
                icon = Icons.Default.BugReport
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(BugReporter.MAX_DESCRIPTION_CHARS) },
                label = { Text(stringResource(R.string.bug_report_desc_label)) },
                placeholder = { Text(stringResource(R.string.bug_report_desc_placeholder)) },
                minLines = 4,
                supportingText = {
                    if (description.isBlank()) Text(stringResource(R.string.bug_report_desc_required))
                },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel(stringResource(R.string.bug_report_attach_header))
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    AttachmentRow(
                        title = stringResource(R.string.bug_report_attach_device),
                        description = stringResource(R.string.bug_report_attach_device_desc),
                        checked = attachDevice,
                        onCheckedChange = { attachDevice = it },
                        innerPadding = spacing.cardInner
                    )
                    AttachmentRow(
                        title = stringResource(R.string.bug_report_attach_log),
                        description = stringResource(R.string.bug_report_attach_log_desc),
                        checked = attachDiagnostics,
                        onCheckedChange = { attachDiagnostics = it },
                        innerPadding = spacing.cardInner
                    )
                    AttachmentRow(
                        title = stringResource(R.string.bug_report_attach_conn),
                        description = stringResource(R.string.bug_report_attach_conn_desc),
                        checked = attachConnection,
                        onCheckedChange = { attachConnection = it },
                        innerPadding = spacing.cardInner
                    )
                    // Bez zaznamenaného pádu není co přikládat - volba je vypnutá.
                    AttachmentRow(
                        title = stringResource(R.string.bug_report_attach_crash),
                        description = if (crashAvailable) {
                            stringResource(R.string.bug_report_attach_crash_desc)
                        } else {
                            stringResource(R.string.bug_report_attach_crash_none)
                        },
                        checked = attachCrash && crashAvailable,
                        enabled = crashAvailable,
                        onCheckedChange = { attachCrash = it },
                        innerPadding = spacing.cardInner
                    )
                }
            }

            InfoCard(
                text = stringResource(R.string.bug_report_privacy),
                icon = Icons.Default.PrivacyTip
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        // Skládání náhledu čte soubor s pádem - tedy na IO vlákno.
                        preview = withContext(Dispatchers.IO) {
                            BugReporter.buildPreview(context, description, options)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bug_report_preview))
            }

            Button(
                onClick = {
                    sendState = SendState.Sending
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            BugReporter.send(context, description, options)
                        }
                        sendState = SendState.Done(result)
                    }
                },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bug_report_send))
            }

            if (sendState == SendState.Sending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.bug_report_sending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Náhled: přesně ten obsah, který odejde na server.
    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.bug_report_preview_title)) },
            text = {
                Text(
                    text = text,
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { preview = null }) { Text(stringResource(R.string.btn_close)) }
            }
        )
    }

    // Výsledek odeslání.
    (sendState as? SendState.Done)?.let { done ->
        val message = when (val result = done.result) {
            BugReporter.Result.Success -> stringResource(R.string.bug_report_result_ok)
            is BugReporter.Result.Failed -> stringResource(errorMessageRes(result.error))
        }
        AlertDialog(
            onDismissRequest = { sendState = SendState.Idle },
            title = { Text(stringResource(R.string.bug_report_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    val success = done.result is BugReporter.Result.Success
                    sendState = SendState.Idle
                    // Po úspěchu není důvod zůstávat - hlášení je pryč.
                    if (success) navController.popBackStack()
                }) { Text(stringResource(R.string.btn_close)) }
            }
        )
    }
}

/** Hláška k důvodu neúspěchu (obě jazykové verze jsou ve strings.xml). */
private fun errorMessageRes(error: BugReporter.Error): Int = when (error) {
    BugReporter.Error.NO_RELAY -> R.string.bug_report_error_no_relay
    BugReporter.Error.TOR_NOT_READY -> R.string.bug_report_error_tor
    BugReporter.Error.NETWORK -> R.string.bug_report_error_network
    BugReporter.Error.SERVER_REJECTED -> R.string.bug_report_error_server
    BugReporter.Error.TOO_LARGE -> R.string.bug_report_error_too_large
}

/** Jeden vypínatelný řádek „co přiložit". */
@Composable
private fun AttachmentRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    innerPadding: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val descColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = innerPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = descColor)
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Nadpis sekce ve stejném stylu jako v Nastavení. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}
