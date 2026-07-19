@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jelenxp.cryptochat.ui.screens

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.theme.LocalUiSpacing
import com.jelenxp.cryptochat.ui.util.AvatarStore
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

@Composable
fun MainScreen(navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    val spacing = LocalUiSpacing.current
    var query by rememberSaveable { mutableStateOf("") }
    // Dlouhý stisk kontaktu → rychlé akce; případné potvrzení smazání.
    var quickActions by remember { mutableStateOf<Contact?>(null) }
    var deleteTarget by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Live filtrování podle jména (bez ohledu na velikost písmen).
    val filtered = remember(contacts, query) {
        val q = query.trim()
        if (q.isEmpty()) contacts
        else contacts.filter { it.name.contains(q, ignoreCase = true) }
    }

    val errorDeleteFailed = stringResource(R.string.error_delete_failed)

    CryptoScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = { navController.navigate("settings") }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.content_desc_settings))
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("add_user") }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.content_desc_add_user))
            }
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.content_desc_clear_search)
                                )
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.search_contacts_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screenPad, vertical = 8.dp)
                )

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(spacing.screenPad),
                        verticalArrangement = Arrangement.spacedBy(spacing.itemGap)
                    ) {
                        items(filtered, key = { it.id }) { contact ->
                            ContactCard(
                                contact = contact,
                                innerPadding = spacing.cardInner,
                                // Plynulé přeskupení při hledání / přidání / smazání.
                                modifier = Modifier.animateItemPlacement(),
                                onClick = { navController.navigate("user_detail/${contact.id}") },
                                onLongClick = { quickActions = contact }
                            )
                        }
                    }
                }
            }
        }
    }

    // Rychlé akce po dlouhém stisku.
    quickActions?.let { c ->
        AlertDialog(
            onDismissRequest = { quickActions = null },
            title = { Text(c.name) },
            text = {
                Column {
                    QuickRow(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.menu_open)) {
                        quickActions = null
                        navController.navigate("user_detail/${c.id}")
                    }
                    if (c.keyBase64 != null) {
                        QuickRow(Icons.Default.VerifiedUser, stringResource(R.string.btn_verify_key)) {
                            quickActions = null
                            navController.navigate("verify/${c.id}")
                        }
                    }
                    QuickRow(Icons.Default.Delete, stringResource(R.string.btn_delete_contact), MaterialTheme.colorScheme.error) {
                        quickActions = null
                        deleteTarget = c
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { quickActions = null }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }

    // Potvrzení smazání.
    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_text, c.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = viewModel.deleteContact(c.id)
                    deleteTarget = null
                    if (ok) AvatarStore.deleteAvatars(context, c.id)
                    else Toast.makeText(context, errorDeleteFailed, Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.btn_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}

@Composable
private fun ContactCard(
    contact: Contact,
    innerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val hasKey = contact.keyBase64 != null
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(innerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ContactAvatar(name = contact.name, avatarPath = contact.avatarPath, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(if (hasKey) R.string.key_set else R.string.key_not_set),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasKey) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (hasKey) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = null,
                tint = if (hasKey) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickRow(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.PersonAddAlt,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.main_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.main_empty_state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
