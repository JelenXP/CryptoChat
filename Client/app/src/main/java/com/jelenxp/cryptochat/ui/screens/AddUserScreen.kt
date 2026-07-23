package com.jelenxp.cryptochat.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard

private const val STEP_NAME = 0
private const val STEP_ROLE = 1

/**
 * Průvodce přidáním kontaktu ve dvou krocích: Jméno → Klíč.
 * Kontakt se navazuje výhradně přes pozvánku (online párování přes relay);
 * dřívější osobní výměna klíče „tváří v tvář" už není součástí flow, takže se
 * krok s výběrem způsobu vypustil.
 *
 * Kroky žijí uvnitř jedné obrazovky (ne přes navigaci), takže zadané jméno
 * zůstane zachované, když se uživatel vrátí o krok zpět. Poslední krok teprve
 * naviguje na konkrétní obrazovku pozvánky (vytvořit / zadat).
 */
@Composable
fun AddUserScreen(
    navController: NavController,
    contactId: String? = null,
    presetName: String? = null
) {
    // Re-key režim: jméno už známe (obnovujeme klíč existujícího kontaktu),
    // takže se přeskočí krok se jménem a začíná se rovnou volbou role.
    val rekey = contactId != null
    val startStep = if (rekey) STEP_ROLE else STEP_NAME

    var step by rememberSaveable { mutableStateOf(startStep) }
    var name by rememberSaveable { mutableStateOf(presetName ?: "") }

    val trimmedName = name.trim()
    val encodedName = Uri.encode(trimmedName)
    // Když obnovujeme klíč, výměna se nasměruje na existující kontakt (zachová
    // jméno i fotku); jinak se založí nový.
    val idSuffix = if (contactId != null) "?contactId=${Uri.encode(contactId)}" else ""

    // Systémové "zpět" projde nejdřív kroky průvodce, teprve pak opustí obrazovku.
    BackHandler(enabled = step > startStep) { step -= 1 }

    CryptoScaffold(
        title = stringResource(if (rekey) R.string.rekey_title else R.string.new_user_title),
        onBack = { if (step > startStep) step -= 1 else navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Ukazatel dvou kroků dává smysl jen u zakládání (Jméno → Klíč).
            if (!rekey) StepIndicator(currentStep = step)

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "wizardStep"
            ) { current ->
                when (current) {
                    STEP_NAME -> NameStep(
                        name = name,
                        onNameChange = { name = it },
                        onContinue = { if (trimmedName.isNotEmpty()) step = STEP_ROLE }
                    )
                    else -> RoleStep(
                        note = if (rekey) stringResource(R.string.rekey_warning) else null,
                        onPrimary = { navController.navigate("pair_invite/$encodedName$idSuffix") },
                        onSecondary = { navController.navigate("pair_join/$encodedName$idSuffix") }
                    )
                }
            }
        }
    }
}

/** Vodorovný ukazatel dvou kroků (Jméno / Klíč) s aktivní/hotovou tečkou. */
@Composable
private fun StepIndicator(currentStep: Int) {
    val labels = listOf(
        stringResource(R.string.step_name),
        stringResource(R.string.step_key)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val done = index < currentStep
            val active = index == currentStep
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else if (done) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                    else if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        else Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active || done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (index < labels.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .padding(bottom = 16.dp)
                        .height(2.dp)
                ) {
                    Surface(
                        color = if (index < currentStep) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.fillMaxSize()
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun NameStep(name: String, onNameChange: (String) -> Unit, onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.add_name_question),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.add_user_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.label_username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onContinue,
            enabled = name.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.btn_continue))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

/**
 * Poslední krok: volba role u pozvánky - vytvořit pozvánku (iniciátor) nebo
 * zadat pozvánku od protějšku (připojení). [note] ukáže varování při obnově klíče.
 */
@Composable
private fun RoleStep(note: String?, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.add_role_title_invite),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.add_role_desc_invite),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Upozornění při obnově klíče (re-key) - dřív viselo na kroku se způsobem.
        note?.let { InfoCard(text = it) }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_invite_create))
        }
        OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_invite_enter))
        }
    }
}
