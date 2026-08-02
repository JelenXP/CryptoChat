package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.WireExt
import com.jelenxp.cryptochat.chat.WireCompat
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.InfoCard
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import com.jelenxp.cryptochat.viewmodel.GroupsViewModel

/**
 * Založení skupiny: název + moje jméno (uvidí ho členové) + volitelný výběr členů.
 * Vybírat jde jen kontakty, které skupiny umí ([WireExt.CAP_GROUPS]) — ostatním by
 * pozvánka stejně nedorazila. Členy lze přidat i později v detailu skupiny, takže
 * když žádný schopný kontakt není, skupina se založí prázdná (jen s adminem).
 */
@Composable
fun CreateGroupScreen(navController: NavController, contactsVm: ContactsViewModel, groupsVm: GroupsViewModel) {
    val ctx = LocalContext.current
    val contacts by contactsVm.contacts.collectAsState()
    val capable = remember(contacts) {
        contacts.filter { it.keyBase64 != null && WireCompat.peerHasCapability(ctx, it.id, WireExt.CAP_GROUPS) }
    }

    var groupName by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf(groupsVm.myDisplayName()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    CryptoScaffold(
        title = stringResource(R.string.new_group),
        onBack = { navController.popBackStack() }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = myName,
                    onValueChange = { myName = it },
                    label = { Text(stringResource(R.string.group_your_name_label)) },
                    supportingText = { Text(stringResource(R.string.group_your_name_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { InfoCard(text = stringResource(R.string.group_create_hint)) }

            item {
                Text(
                    stringResource(R.string.group_add_members),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (capable.isEmpty()) {
                item { Text(stringResource(R.string.group_no_capable_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(capable, key = { it.id }) { contact ->
                    SelectableContactRow(
                        contact = contact,
                        checked = contact.id in selected,
                        onToggle = {
                            selected = if (contact.id in selected) selected - contact.id else selected + contact.id
                        }
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (creating) return@Button
                        creating = true
                        scope.launch {
                            val group = groupsVm.createGroup(groupName, myName)
                            if (group == null) { creating = false; return@launch }
                            capable.filter { it.id in selected }.forEach { groupsVm.invite(group, it) }
                            navController.navigate("group_chat/${group.groupId}") { popUpTo("main") }
                        }
                    },
                    enabled = groupName.isNotBlank() && myName.isNotBlank() && !creating,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.create_group))
                }
            }
        }
    }
}

@Composable
private fun SelectableContactRow(contact: Contact, checked: Boolean, onToggle: () -> Unit) {
    AppCard(onClick = onToggle) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(name = contact.name, avatarPath = contact.avatarPath, size = 40.dp)
            Text(
                contact.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}
