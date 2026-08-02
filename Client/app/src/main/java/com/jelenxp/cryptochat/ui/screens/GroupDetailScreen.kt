package com.jelenxp.cryptochat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.GroupMemberNames
import com.jelenxp.cryptochat.chat.GroupRoster
import com.jelenxp.cryptochat.chat.WireCompat
import com.jelenxp.cryptochat.chat.WireExt
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.ui.util.AvatarStore
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import com.jelenxp.cryptochat.viewmodel.GroupsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail skupiny: fotka + název, seznam členů (s odznakem Admin/Vy), a — pro admina —
 * přidání/odebrání členů. Fotka je lokální (nastavit/odebrat kdykoli). Opuštění =
 * smazání lokální kopie (nástupnictví admina není). Skupina zmizí → zpět na seznam.
 */
@Composable
fun GroupDetailScreen(groupId: String, navController: NavController, groupsVm: GroupsViewModel, contactsVm: ContactsViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by groupsVm.groups.collectAsState()
    val group = groups.find { it.groupId == groupId }
    if (group == null) {
        androidx.compose.runtime.LaunchedEffect(groupId) { navController.popBackStack() }
        return
    }

    val contacts by contactsVm.contacts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<GroupRoster.Member?>(null) }
    var showLeaveDialog by remember { mutableStateOf(false) }

    // Jména členů (lokální kontakt má přednost jako u 1:1) — čte Keystore, tedy mimo hlavní vlákno.
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(group.rosterBytesBase64) {
        names = withContext(Dispatchers.IO) { GroupMemberNames.resolvedNames(ctx, group) }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { AvatarStore.saveAvatar(ctx, groupId, uri) }
            if (path != null) groupsVm.setAvatar(groupId, path)
        }
    }

    CryptoScaffold(
        title = stringResource(R.string.group_members),
        onBack = { navController.popBackStack() }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Hlavička: fotka + název (klepnutí na fotku = vybrat/změnit).
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.clickable { photoPicker.launch("image/*") }) {
                        ContactAvatar(name = group.name, avatarPath = group.avatarPath, size = 96.dp)
                    }
                    Text(
                        group.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (group.avatarPath != null) {
                        TextButton(onClick = { groupsVm.setAvatar(groupId, null) }) {
                            Text(stringResource(R.string.group_remove_photo))
                        }
                    } else {
                        TextButton(onClick = { photoPicker.launch("image/*") }) {
                            Text(stringResource(R.string.group_set_photo))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.group_member_count, group.members().size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (group.amIAdmin) {
                        OutlinedButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.group_add_member))
                        }
                    }
                }
            }

            items(group.members(), key = { it.memberIdHex }) { member ->
                MemberRow(
                    member = member,
                    displayName = names[member.memberIdHex] ?: member.displayName,
                    isAdmin = member.memberIdHex == group.adminMemberId,
                    isMe = member.memberIdHex == group.myMemberId,
                    canRemove = group.amIAdmin && member.memberIdHex != group.myMemberId && member.memberIdHex != group.adminMemberId,
                    onRemove = { removeTarget = member }
                )
            }

            item {
                Button(
                    onClick = { showLeaveDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(stringResource(R.string.group_leave))
                }
            }
        }
    }

    // --- dialogy ---

    if (showAddDialog) {
        val capable = remember(contacts) {
            contacts.filter { it.keyBase64 != null && WireCompat.peerHasCapability(ctx, it.id, WireExt.CAP_GROUPS) }
        }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.group_add_member)) },
            text = {
                if (capable.isEmpty()) {
                    Text(stringResource(R.string.group_no_capable_contacts))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(capable, key = { it.id }) { c ->
                            AppCard(onClick = {
                                groupsVm.invite(group, c)
                                showAddDialog = false
                            }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ContactAvatar(name = c.name, avatarPath = c.avatarPath, size = 36.dp)
                                    Text(c.name, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddDialog = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    removeTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.group_remove_member)) },
            text = { Text(stringResource(R.string.group_remove_member_confirm, member.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    groupsVm.removeMember(group, member.memberIdHex, contacts)
                    removeTarget = null
                }) { Text(stringResource(R.string.group_remove_member)) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.group_leave)) },
            text = { Text(stringResource(R.string.group_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    groupsVm.deleteGroup(groupId)
                    navController.popBackStack("main", inclusive = false)
                }) { Text(stringResource(R.string.group_leave)) }
            },
            dismissButton = { TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun MemberRow(
    member: GroupRoster.Member,
    displayName: String,
    isAdmin: Boolean,
    isMe: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(name = displayName, avatarPath = null, size = 40.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(displayName, style = MaterialTheme.typography.bodyLarge)
                val adminLabel = stringResource(R.string.group_admin_label)
                val youLabel = stringResource(R.string.group_you_label)
                val tags = buildList {
                    if (isAdmin) add(adminLabel)
                    if (isMe) add(youLabel)
                }
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.group_remove_member))
                }
            }
        }
    }
}
