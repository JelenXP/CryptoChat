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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.chat.DraftStore
import com.jelenxp.cryptochat.chat.RelayConn
import com.jelenxp.cryptochat.chat.RelayStatus
import com.jelenxp.cryptochat.data.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.jelenxp.cryptochat.ui.components.AppCard
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.CryptoScaffold
import com.jelenxp.cryptochat.ui.components.LocalUiBlocked
import com.jelenxp.cryptochat.ui.theme.LocalUiSpacing
import com.jelenxp.cryptochat.ui.util.AvatarStore
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel

/**
 * Poslední spočítané náhledy konverzací (poslední zpráva + počet nepřečtených),
 * klíčované id kontaktu. Drží se mimo kompozici, aby byl návrat na seznam
 * okamžitý a nic neproblikávalo - spočítat je znovu totiž znamená dešifrovat
 * historii, což na hlavní vlákno nepatří.
 */
private var previewCache: Map<String, Pair<ChatMessage?, Int>> = emptyMap()

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

    // Spojení se serverem chatu se testuje samo - po startu appky i po návratu
    // do popředí (např. ze změny serveru). Výsledek ukazuje indikátor u ikony
    // cloudu (kolečko → fajfka); žádné toasty.
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                scope.launch { RelayStatus.refresh(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Náhledy konverzací (poslední zpráva + počet nepřečtených). Čtou se lokálně
    // z historie a obnovují se, dokud je seznam v popředí, aby se aktualizoval
    // živě i když zprávy dorazí na pozadí.
    val chatRepo = remember { ChatRepository(context) }
    // Náhledy se drží mezi vstupy na obrazovku, takže při návratu jsou hned k
    // dispozici a nic neprobliká. Číst je v kompozici NELZE - `getLastMessage`
    // dešifruje historii Keystorem a při víc kontaktech by to na hlavním vlákně
    // znamenalo zamrznutí až ANR.
    var previews by remember { mutableStateOf(previewCache) }
    // Leží nad seznamem blokující překryv? Pak se poll níž pozastaví.
    val uiBlocked = LocalUiBlocked.current
    // Rozepsané drafty (contactId -> text) pro indikátor „Rozepsáno" v seznamu.
    // Všechny naráz jedním dešifrováním (DraftStore drží jeden blob).
    var drafts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val draftStore = remember { DraftStore(context) }
    LaunchedEffect(contacts) {
        // První načtení hned (ne až po prodlevě), ale na IO vlákně.
        previews = withContext(Dispatchers.IO) {
            contacts.associate { c ->
                c.id to (chatRepo.getLastMessage(c.id) to chatRepo.getUnreadCount(c.id))
            }
        }.also { previewCache = it }
        drafts = withContext(Dispatchers.IO) { draftStore.all() }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Pod blokujícím překryvem (zámek, Novinky, upozornění na aktualizaci)
            // seznam stejně není vidět ani ovladatelný - a překreslování s
            // animací přesunů by konkurovalo o hlavní vlákno právě ve chvíli, kdy
            // uživatel klepe na tlačítka v překryvu. Viz [LocalUiBlocked].
            snapshotFlow { uiBlocked }.collectLatest { blocked ->
                if (blocked) return@collectLatest
                while (true) {
                    // Každý průchod dešifruje historii všech konverzací přes Keystore,
                    // takže krátký interval by při rozsvícené obrazovce zbytečně žral
                    // CPU. 5 s je na živý náhled pořád dost svižné.
                    delay(5000)
                    val fresh = withContext(Dispatchers.IO) {
                        contacts.associate { c ->
                            c.id to (chatRepo.getLastMessage(c.id) to chatRepo.getUnreadCount(c.id))
                        }
                    }
                    val freshDrafts = withContext(Dispatchers.IO) { draftStore.all() }
                    // Přiřaď jen při změně, ať se seznam zbytečně nerekomponuje.
                    if (fresh != previews) {
                        previews = fresh
                        previewCache = fresh
                    }
                    if (freshDrafts != drafts) drafts = freshDrafts
                }
            }
        }
    }

    // Řazení podle poslední aktivity (nejnověji psané nahoře) + live filtrování
    // podle jména. Obojí je v MainScreenLogic, ať jde otestovat. Klíč řazení bere
    // čas poslední zprávy z náhledů (počítají se mimo hlavní vlákno); dokud se
    // nenačtou, drží se pořadí ze storage a přeskládá se, jakmile náhledy dojdou.
    val filtered = remember(contacts, previews, query) {
        val lastActivity = previews.mapValues { it.value.first?.timestamp ?: Long.MIN_VALUE }
        MainScreenLogic.filterContacts(
            MainScreenLogic.sortByActivity(contacts, lastActivity),
            query
        )
    }

    val errorDeleteFailed = stringResource(R.string.error_delete_failed)

    CryptoScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            // Stav serveru chatu je vidět přímo na ikoně (žádný překryv, žádné
            // toasty): testuje se → kolečko, připojeno → cloud s fajfkou,
            // nedostupné → přeškrtnutý cloud, nenastaveno → obyčejný cloud.
            IconButton(onClick = { navController.navigate("relay_settings") }) {
                val relayDesc = stringResource(R.string.content_desc_relay)
                when (RelayStatus.state) {
                    RelayConn.CONNECTING -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    RelayConn.CONNECTED -> Icon(
                        Icons.Default.CloudDone,
                        contentDescription = relayDesc,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    RelayConn.FAILED -> Icon(
                        Icons.Default.CloudOff,
                        contentDescription = relayDesc,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RelayConn.UNKNOWN -> Icon(
                        Icons.Default.CloudQueue,
                        contentDescription = relayDesc
                    )
                }
            }
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
                            val preview = previews[contact.id]
                            ContactCard(
                                contact = contact,
                                lastMessage = preview?.first,
                                unread = preview?.second ?: 0,
                                innerPadding = spacing.cardInner,
                                // Plynulé přeskupení při hledání / přidání / smazání.
                                modifier = Modifier.animateItemPlacement(),
                                // Klik otevře konverzaci; detail/klíč je pod dlouhým stiskem.
                                onClick = { navController.navigate("chat/${contact.id}") },
                                onLongClick = { quickActions = contact },
                                draft = drafts[contact.id]
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
    lastMessage: ChatMessage?,
    unread: Int,
    innerPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    draft: String? = null
) {
    val hasKey = contact.keyBase64 != null
    val isUnread = unread > 0
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
                    fontWeight = if (isUnread) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Rozhodnutí, co ukázat, je v MainScreenLogic (testovatelné);
                // tady se jen přeloží na text. Odchozí má prefix „Ty:".
                val s = MainScreenLogic.contactSubtitle(hasKey, lastMessage, draft)
                val subtitle = when (s) {
                    MainScreenLogic.Subtitle.NoKey -> stringResource(R.string.key_not_set)
                    MainScreenLogic.Subtitle.NoMessages -> stringResource(R.string.chat_preview_none)
                    is MainScreenLogic.Subtitle.Draft -> stringResource(R.string.chat_preview_draft, s.text)
                    is MainScreenLogic.Subtitle.Deleted -> {
                        val body = stringResource(R.string.chat_message_deleted)
                        if (s.fromMe) stringResource(R.string.chat_last_you, body) else body
                    }
                    is MainScreenLogic.Subtitle.Last -> {
                        val body = when (s.kind) {
                            ChatMessage.Kind.IMAGE -> stringResource(R.string.chat_preview_photo)
                            ChatMessage.Kind.FILE -> stringResource(R.string.chat_preview_file)
                            else -> s.text
                        }
                        if (s.fromMe) stringResource(R.string.chat_last_you, body) else body
                    }
                }
                val isDraft = s is MainScreenLogic.Subtitle.Draft
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Rozepsáno zvýrazni značkovou barvou (jako u běžných messengerů).
                    color = when {
                        isDraft -> MaterialTheme.colorScheme.primary
                        isUnread -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isUnread) FontWeight.SemiBold else null
                )
            }
            // Vpravo: čas poslední zprávy a pod ním počet nepřečtených (jako
            // u běžných messengerů). Když ještě žádná zpráva není, zůstane zámek.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (lastMessage != null) {
                    Text(
                        text = formatPreviewTime(lastMessage.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUnread) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    isUnread -> UnreadBadge(unread)
                    lastMessage == null -> Icon(
                        imageVector = if (hasKey) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (hasKey) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Čas poslední zprávy pro seznam: dnešní zprávy jako „HH:mm", starší jako
 * krátké datum podle jazyka telefonu.
 */
private fun formatPreviewTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    } else {
        DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(timestamp))
    }
}

/** Kolečko s počtem nepřečtených zpráv (značkový akcent). */
@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary
        )
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
