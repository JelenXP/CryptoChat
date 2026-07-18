package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

@Composable
fun UserDetailScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val contact = viewModel.getContact(id)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editedName by remember(contact?.name) { mutableStateOf(contact?.name ?: "") }

    val errorSaveFailed = stringResource(R.string.error_save_failed)
    val errorDeleteFailed = stringResource(R.string.error_delete_failed)

    CryptoScaffold(
        title = contact?.name ?: stringResource(R.string.user_fallback_title),
        onBack = { navController.popBackStack() },
        actions = {
            if (contact != null) {
                IconButton(onClick = {
                    editedName = contact.name
                    showEditDialog = true
                }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.btn_edit_name))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                contact == null -> InfoCard(text = stringResource(R.string.user_not_found))
                else -> {
                    ContactHeader(name = contact.name, hasKey = contact.keyBase64 != null)

                    if (contact.keyBase64 == null) {
                        InfoCard(text = stringResource(R.string.user_no_key))
                    } else {
                        Button(
                            onClick = { navController.navigate("send/$id") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_send))
                        }

                        FilledTonalButton(
                            onClick = { navController.navigate("receive/$id") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MoveToInbox, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_receive))
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_delete_contact))
                    }
                }
            }
        }
    }

    if (showEditDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.dialog_edit_name_title)) },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text(stringResource(R.string.label_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editedName.trim()
                        if (trimmed.isNotEmpty()) {
                            val success = viewModel.addOrUpdateContact(contact.copy(name = trimmed))
                            showEditDialog = false
                            if (!success) {
                                Toast.makeText(context, errorSaveFailed, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = editedName.isNotBlank()
                ) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    if (showDeleteDialog && contact != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_text, contact.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val success = viewModel.deleteContact(id)
                    showDeleteDialog = false
                    if (success) {
                        navController.popBackStack("main", inclusive = false)
                    } else {
                        Toast.makeText(context, errorDeleteFailed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.btn_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

/** Hlavička detailu: velký avatar, jméno a stav klíče. */
@Composable
private fun ContactHeader(name: String, hasKey: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (hasKey) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(if (hasKey) R.string.key_set else R.string.key_not_set),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasKey) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
