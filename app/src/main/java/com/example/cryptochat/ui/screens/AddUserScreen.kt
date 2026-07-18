package com.example.cryptochat.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.cryptochat.R
import com.example.cryptochat.ui.components.AppCard
import com.example.cryptochat.ui.components.CryptoScaffold

@Composable
fun AddUserScreen(navController: NavController) {
    var name by remember { mutableStateOf("") }
    val trimmedName = name.trim()
    val nameValid = trimmedName.isNotEmpty()
    val encodedName = Uri.encode(trimmedName)

    CryptoScaffold(
        title = stringResource(R.string.new_user_title),
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
            Text(
                text = stringResource(R.string.add_user_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ExchangeCard(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.section_in_person),
                description = stringResource(R.string.section_in_person_description),
                primaryLabel = stringResource(R.string.btn_create_key),
                onPrimary = { navController.navigate("create_key/$encodedName") },
                secondaryLabel = stringResource(R.string.btn_accept_key),
                onSecondary = { navController.navigate("accept_key/$encodedName") },
                enabled = nameValid
            )

            ExchangeCard(
                icon = Icons.Default.Public,
                title = stringResource(R.string.section_remote),
                description = stringResource(R.string.section_remote_description),
                primaryLabel = stringResource(R.string.btn_remote_init),
                onPrimary = { navController.navigate("remote_init/$encodedName") },
                secondaryLabel = stringResource(R.string.btn_remote_complete),
                onSecondary = { navController.navigate("remote_complete/$encodedName") },
                enabled = nameValid
            )
        }
    }
}

/**
 * Karta jednoho způsobu výměny klíče (osobně / na dálku): ikona, nadpis,
 * vysvětlení a dvojice tlačítek pro obě role (zahájit / dokončit).
 */
@Composable
private fun ExchangeCard(
    icon: ImageVector,
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    enabled: Boolean
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onPrimary,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text(primaryLabel) }
            OutlinedButton(
                onClick = onSecondary,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text(secondaryLabel) }
        }
    }
}
