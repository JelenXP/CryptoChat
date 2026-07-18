package com.jelenxp.cryptochat.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.CryptoScaffold

private const val STEP_NAME = 0
private const val STEP_METHOD = 1
private const val STEP_ROLE = 2

private const val METHOD_IN_PERSON = "in_person"
private const val METHOD_REMOTE = "remote"

/**
 * Průvodce přidáním kontaktu ve třech krocích: Jméno → Způsob → Klíč.
 * Kroky žijí uvnitř jedné obrazovky (ne přes navigaci), takže zadané jméno i
 * volba způsobu zůstanou zachované, když se uživatel vrátí o krok zpět.
 * Poslední krok teprve naviguje na konkrétní obrazovku výměny klíče.
 */
@Composable
fun AddUserScreen(navController: NavController) {
    var step by rememberSaveable { mutableStateOf(STEP_NAME) }
    var name by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(METHOD_IN_PERSON) }

    val trimmedName = name.trim()
    val encodedName = Uri.encode(trimmedName)

    // Systémové "zpět" projde nejdřív kroky průvodce, teprve pak opustí obrazovku.
    BackHandler(enabled = step > STEP_NAME) { step -= 1 }

    CryptoScaffold(
        title = stringResource(R.string.new_user_title),
        onBack = { if (step > STEP_NAME) step -= 1 else navController.popBackStack() }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StepIndicator(currentStep = step)

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "wizardStep"
            ) { current ->
                when (current) {
                    STEP_NAME -> NameStep(
                        name = name,
                        onNameChange = { name = it },
                        onContinue = { if (trimmedName.isNotEmpty()) step = STEP_METHOD }
                    )
                    STEP_METHOD -> MethodStep(
                        name = trimmedName,
                        onChoose = { chosen -> method = chosen; step = STEP_ROLE }
                    )
                    else -> RoleStep(
                        method = method,
                        onPrimary = {
                            val route = if (method == METHOD_IN_PERSON) "create_key" else "remote_init"
                            navController.navigate("$route/$encodedName")
                        },
                        onSecondary = {
                            val route = if (method == METHOD_IN_PERSON) "accept_key" else "remote_complete"
                            navController.navigate("$route/$encodedName")
                        }
                    )
                }
            }
        }
    }
}

/** Vodorovný ukazatel tří kroků (Jméno / Způsob / Klíč) s aktivní/hotovou tečkou. */
@Composable
private fun StepIndicator(currentStep: Int) {
    val labels = listOf(
        stringResource(R.string.step_name),
        stringResource(R.string.step_method),
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

@Composable
private fun MethodStep(name: String, onChoose: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.add_method_question, name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = stringResource(R.string.add_method_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        MethodCard(
            icon = Icons.Default.Groups,
            title = stringResource(R.string.section_in_person),
            description = stringResource(R.string.section_in_person_description),
            onClick = { onChoose(METHOD_IN_PERSON) }
        )
        MethodCard(
            icon = Icons.Default.Public,
            title = stringResource(R.string.section_remote),
            description = stringResource(R.string.section_remote_description),
            onClick = { onChoose(METHOD_REMOTE) }
        )
    }
}

@Composable
private fun MethodCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    AppCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoleStep(method: String, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val inPerson = method == METHOD_IN_PERSON
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(
                if (inPerson) R.string.add_role_title_in_person else R.string.add_role_title_remote
            ),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(
                if (inPerson) R.string.add_role_desc_in_person else R.string.add_role_desc_remote
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (inPerson) R.string.btn_create_key else R.string.btn_remote_init))
        }
        OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (inPerson) R.string.btn_accept_key else R.string.btn_remote_complete))
        }
    }
}
