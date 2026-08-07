package com.jelenxp.cryptochat.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.ActiveChat
import com.jelenxp.cryptochat.chat.ChatMediaStore
import com.jelenxp.cryptochat.chat.ChatNotifications
import com.jelenxp.cryptochat.chat.GroupChatMessage
import com.jelenxp.cryptochat.chat.GroupChatRepository
import com.jelenxp.cryptochat.chat.GroupMediaStore
import com.jelenxp.cryptochat.chat.GroupMemberNames
import com.jelenxp.cryptochat.chat.GroupStore
import com.jelenxp.cryptochat.chat.GroupSync
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.AboveAnchorPosition
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.components.IncognitoTextField
import com.jelenxp.cryptochat.ui.components.ReactionPicker
import com.jelenxp.cryptochat.ui.emoji.EmojiPickerSheet
import com.jelenxp.cryptochat.ui.theme.LocalDesign
import com.jelenxp.cryptochat.viewmodel.GroupsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GROUP_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Paleta rychlých reakcí (dlouhý stisk bubliny) + výchozí pro dvojklik. */
private val GROUP_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private const val GROUP_DEFAULT_REACTION = "👍"

/** Jak daleko se musí bublina odtáhnout, aby se spustila odpověď (stejné jako 1:1). */
private const val GROUP_SWIPE_REPLY_THRESHOLD_PX = 180f

/**
 * Skupinová konverzace — záměrně STEJNÁ jako 1:1 [ChatScreen]: bubliny, oddělovače dnů,
 * reakce, odpovědi, VÝBĚROVÝ REŽIM (dlouhý stisk → výběr + plovoucí paleta reakcí + akční
 * lišta: odpovědět / upravit / kopírovat / smazat), úprava, mazání pro všechny i u sebe,
 * fullscreen fotka, hledání a skok na konec. Navíc jen jméno odesílatele nad příchozí
 * bublinou (LOKÁLNÍ jméno kontaktu má přednost) a systémové řádky „X se připojil". Příjem
 * obstarává služba; obrazovka jen přenačítá.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(groupId: String, navController: NavController, groupsViewModel: GroupsViewModel) {
    val ctx = LocalContext.current
    val groups by groupsViewModel.groups.collectAsState()
    val group = groups.find { it.groupId == groupId }

    if (group == null) {
        LaunchedEffect(groupId) { navController.popBackStack() }
        return
    }

    var messages by remember { mutableStateOf(emptyList<GroupChatMessage>()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) } // proti bliku „no messages yet" před 1. načtením
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var emojiPickerFor by remember { mutableStateOf<String?>(null) } // msgId s otevřeným plným emoji pickerem
    var replyTo by remember { mutableStateOf<GroupChatMessage?>(null) }
    var highlightedId by remember { mutableStateOf<String?>(null) } // krátce zvýrazněná po skoku z citace
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) } // výběrový režim (jako 1:1)
    var editing by remember { mutableStateOf<GroupChatMessage?>(null) }     // upravovaná zpráva
    var draftBeforeEdit by remember { mutableStateOf<String?>(null) }        // rozepsaný koncept před úpravou
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var fullscreenImagePath by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<GroupChatMessage>?>(null) }
    var stickToBottom by rememberSaveable { mutableStateOf(true) } // přežije rotaci/proces (jako 1:1)

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val bottomTolerancePx = remember(density) { with(density) { 48.dp.roundToPx() } }

    // V hledání se seznam zúží na shody a zploští (bez oddělovačů dnů), jako 1:1.
    val visibleMessages = if (searchMode) GroupChatScreenLogic.filterMessages(messages, searchQuery) else messages
    val rows = remember(visibleMessages, searchMode) {
        if (searchMode) visibleMessages.map { GroupChatRows.Row.Msg(it) } else GroupChatRows.build(visibleMessages)
    }
    // Index pro dohledání citace + prořezání výběru/odpovědi/úpravy, když cíl zmizí (jako 1:1).
    val byMsgId = remember(messages) { GroupChatScreenLogic.msgIdIndex(messages) }
    LaunchedEffect(messages) {
        replyTo = GroupChatScreenLogic.survivingReply(messages, replyTo)
        selectedIds = GroupChatScreenLogic.survivingIds(messages, selectedIds)
        if (editing != null && !GroupChatScreenLogic.survivingEdit(messages, editing?.msgIdHex)) {
            input = draftBeforeEdit ?: ""; draftBeforeEdit = null; editing = null
        }
    }

    // Je seznam „u dna"? Řídí skok-na-konec i auto-sledování nových zpráv.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            ChatScreenLogic.isAtBottom(
                lastVisibleIndex = last?.index,
                lastVisibleItemEnd = (last?.offset ?: 0) + (last?.size ?: 0),
                totalItems = info.totalItemsCount,
                viewportEnd = info.viewportEndOffset,
                tolerancePx = bottomTolerancePx
            )
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) stickToBottom = atBottom }
    }

    // Na popředí: potlač notifikaci a přenačítej historii + jména (příjem dělá služba).
    LaunchedEffect(groupId) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ActiveChat.currentGroupId = groupId
            ChatNotifications.cancelGroupMessage(ctx, groupId)
            try {
                while (true) {
                    val snapshot = withContext(Dispatchers.IO) {
                        val fresh = GroupStore(ctx).getGroup(groupId) ?: group
                        GroupChatRepository(ctx).getMessages(groupId) to GroupMemberNames.resolvedNames(ctx, fresh)
                    }
                    messages = snapshot.first
                    names = snapshot.second
                    loaded = true
                    delay(1500)
                }
            } finally {
                ActiveChat.currentGroupId = null
            }
        }
    }

    // Drž se u dna při novém řádku — jen když u dna jsme a nehledáme (jako 1:1).
    LaunchedEffect(rows.size, searchMode) {
        if (stickToBottom && !searchMode && rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex, SCROLL_BOTTOM_OFFSET)
    }

    fun reload() {
        scope.launch { messages = withContext(Dispatchers.IO) { GroupChatRepository(ctx).getMessages(groupId) } }
    }

    // Skok na citovanou zprávu: doroluj na řádek a krátce zvýrazni (jako 1:1). Nedrž dno,
    // ať keeper zprávu hned nestrhne zpět dolů.
    fun jumpToMessage(target: GroupChatMessage) {
        val idx = rows.indexOfFirst { it is GroupChatRows.Row.Msg && it.message.msgIdHex == target.msgIdHex }
        if (idx < 0) return
        stickToBottom = false
        scope.launch {
            listState.animateScrollToItem(idx)
            highlightedId = target.msgIdHex
            delay(1500)
            if (highlightedId == target.msgIdHex) highlightedId = null
        }
    }

    fun cancelEditing() {
        if (editing == null) return // není co rušit → nesahej na rozepsaný text (audit groups-4)
        input = draftBeforeEdit ?: ""; draftBeforeEdit = null; editing = null
    }

    fun startEditing(m: GroupChatMessage) {
        if (editing == null) draftBeforeEdit = input // při přepnutí edit→edit neztrať původní koncept (audit groups-7)
        editing = m
        input = m.text
        replyTo = null
        selectedIds = emptySet()
        searchMode = false
    }

    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty()) return
        val ed = editing
        if (ed != null) { // odeslání ÚPRAVY
            input = ""; editing = null; draftBeforeEdit = null
            scope.launch {
                val url = SettingsRepository(ctx).getRelayUrl()
                withContext(Dispatchers.IO) { GroupSync.sendEdit(ctx, group, ed.msgIdHex, text, url, System.currentTimeMillis()) }
                reload()
            }
            return
        }
        input = ""
        val reply = replyTo?.msgIdHex
        replyTo = null // optimisticky zavři náhled odpovědi
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) { GroupSync.sendText(ctx, group, text, url, System.currentTimeMillis(), reply) }
            reload()
        }
    }

    // Reakce (emoji, prázdné = zrušení). OPTIMISTICKY hned v UI (než dojde na server),
    // reload smyčka pak stav dorovná; síť běží na pozadí.
    fun react(targetMsgId: String, emoji: String) {
        messages = messages.map { m ->
            if (m.msgIdHex != targetMsgId) m
            else m.copy(reactions = if (emoji.isEmpty()) m.reactions - group.myMemberId else m.reactions + (group.myMemberId to emoji))
        }
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) { GroupSync.sendReaction(ctx, group, targetMsgId, emoji, url, System.currentTimeMillis()) }
        }
    }

    fun retry(m: GroupChatMessage) {
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) {
                GroupChatRepository(ctx).setStatus(groupId, m.msgIdHex, GroupChatMessage.Status.SENDING)
                GroupSync.resend(ctx, group, m.msgIdHex, url, System.currentTimeMillis())
            }
            reload()
        }
    }

    fun deleteForEveryone(list: List<GroupChatMessage>) {
        selectedIds = emptySet()
        scope.launch {
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) {
                for (m in list) GroupSync.sendDeleteForEveryone(ctx, group, m.msgIdHex, url, System.currentTimeMillis())
            }
            reload()
        }
    }

    fun deleteForMe(list: List<GroupChatMessage>) {
        selectedIds = emptySet()
        scope.launch {
            withContext(Dispatchers.IO) { for (m in list) GroupChatRepository(ctx).deleteForMe(groupId, m.msgIdHex) }
            reload()
        }
    }

    fun copySelected() {
        val text = GroupChatScreenLogic.copyText(messages, selectedIds)
        if (text.isNotBlank()) {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(ctx, R.string.chat_copied, Toast.LENGTH_SHORT).show()
        }
        selectedIds = emptySet()
    }

    fun sendImage(uri: Uri) {
        val reply = replyTo?.msgIdHex
        replyTo = null
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { ChatMediaStore.compress(ctx, uri) } ?: return@launch
            val url = SettingsRepository(ctx).getRelayUrl()
            withContext(Dispatchers.IO) {
                val path = GroupMediaStore.save(ctx, groupId, jpeg)
                GroupSync.sendImage(ctx, group, jpeg, path, url, System.currentTimeMillis(), reply)
            }
            reload()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendImage(uri)
    }

    // Systémové zpět postupně zavírá režimy (hledání → úprava → výběr → odpověď), jako 1:1.
    BackHandler(enabled = searchMode || editing != null || selectedIds.isNotEmpty() || replyTo != null) {
        when {
            searchMode -> { searchMode = false; searchQuery = "" }
            editing != null -> cancelEditing()
            selectedIds.isNotEmpty() -> selectedIds = emptySet()
            else -> replyTo = null
        }
    }

    val selectedMsgs = messages.filter { it.msgIdHex in selectedIds }
    val single = selectedMsgs.singleOrNull()

    Scaffold(
        topBar = {
            when {
                selectedIds.isNotEmpty() -> GroupSelectionTopBar(
                    count = selectedIds.size,
                    canReply = single != null && !single.deleted,
                    canEdit = single != null && GroupChatScreenLogic.canEdit(single),
                    canCopy = GroupChatScreenLogic.copyText(messages, selectedIds).isNotBlank(),
                    onClose = { selectedIds = emptySet() },
                    onReply = { cancelEditing(); single?.let { replyTo = it }; selectedIds = emptySet() },
                    onEdit = { single?.let { startEditing(it) } },
                    onCopy = { copySelected() },
                    onDelete = { pendingDelete = selectedMsgs; selectedIds = emptySet() }
                )
                searchMode -> SearchTopBar(searchQuery, { searchQuery = it }, { searchMode = false; searchQuery = "" })
                else -> TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { navController.navigate("group_detail/$groupId") }
                        ) {
                            ContactAvatar(name = group.name, avatarPath = group.avatarPath, size = 32.dp)
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(R.string.group_member_count, group.members().size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_search_hint)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                onClick = { menuOpen = false; searchQuery = ""; searchMode = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_open_detail)) },
                                onClick = { menuOpen = false; navController.navigate("group_detail/$groupId") }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_leave)) },
                                onClick = { menuOpen = false; showLeaveDialog = true }
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (loaded && rows.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (searchMode) R.string.chat_search_no_results else R.string.group_empty_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                    ) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is GroupChatRows.Row.Day -> ChatDayDivider(row.epochDay)
                                is GroupChatRows.Row.Msg -> {
                                    val m = row.message
                                    if (m.isSystem) {
                                        GroupSystemRow(m, myMemberId = group.myMemberId)
                                    } else {
                                        val quote = GroupChatScreenLogic.resolveQuote(m, byMsgId)
                                        val quotedAuthor = when {
                                            quote.message == null -> ""
                                            quote.message.outgoing -> stringResource(R.string.chat_reply_you)
                                            else -> quote.message.senderMemberIdHex?.let { names[it] }
                                                ?: stringResource(R.string.group_reply_member)
                                        }
                                        GroupMessageBubble(
                                            message = m,
                                            senderName = m.senderMemberIdHex?.let { names[it] },
                                            myMemberId = group.myMemberId,
                                            quoted = quote.message,
                                            quotedMissing = quote.missing,
                                            quotedAuthor = quotedAuthor,
                                            highlighted = m.msgIdHex == highlightedId,
                                            highlightQuery = if (searchMode) searchQuery else "",
                                            selected = m.msgIdHex in selectedIds,
                                            selectionMode = selectedIds.isNotEmpty(),
                                            showReactionPicker = selectedIds == setOf(m.msgIdHex),
                                            onReact = { emoji -> react(m.msgIdHex, emoji); selectedIds = emptySet() },
                                            onMore = { emojiPickerFor = m.msgIdHex; selectedIds = emptySet() },
                                            onReplySwipe = { if (!m.deleted) { replyTo = m; selectedIds = emptySet(); cancelEditing() } },
                                            onQuoteClick = { quote.message?.let { jumpToMessage(it) } },
                                            onSelect = { selectedIds = selectedIds + m.msgIdHex },
                                            onTapInSelection = { selectedIds = GroupChatScreenLogic.toggleSelection(selectedIds, m.msgIdHex) },
                                            onDismissReactions = { selectedIds = emptySet() },
                                            onRetry = { retry(m) },
                                            onImageClick = { m.mediaPath?.let { fullscreenImagePath = it } }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (!atBottom && !searchMode && rows.isNotEmpty()) {
                    JumpToBottomButton(
                        count = 0,
                        onClick = { scope.launch { listState.animateScrollToItem(rows.lastIndex, SCROLL_BOTTOM_OFFSET); stickToBottom = true } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    )
                }
            }

            if (!searchMode) {
                // Nad vstupem: náhled ÚPRAVY (přednost) nebo ODPOVĚDI (jako 1:1).
                if (editing != null) {
                    GroupEditComposerPreview(editing!!, onCancel = { cancelEditing() })
                } else replyTo?.let { r ->
                    val author = if (r.outgoing) stringResource(R.string.chat_reply_you)
                    else r.senderMemberIdHex?.let { names[it] } ?: stringResource(R.string.group_reply_member)
                    GroupReplyComposerPreview(message = r, author = author, onCancel = { replyTo = null })
                }
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (editing == null) { // fotku nelze přiložit k úpravě
                            Box {
                                IconButton(onClick = { attachMenu = true }) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.content_desc_attach))
                                }
                                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.attach_gallery)) },
                                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                                        onClick = {
                                            attachMenu = false
                                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        }
                                    )
                                }
                            }
                        }
                        IncognitoTextField(
                            value = input,
                            onValueChange = { input = it },
                            hint = stringResource(R.string.chat_input_hint),
                            enabled = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledIconButton(onClick = { sendCurrent() }, enabled = input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.content_desc_send))
                        }
                    }
                }
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.group_leave)) },
            text = { Text(stringResource(R.string.group_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    groupsViewModel.deleteGroup(groupId)
                    navController.popBackStack()
                }) { Text(stringResource(R.string.group_leave)) }
            },
            dismissButton = { TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    // Potvrzení mazání — jako 1:1: když jsou VŠECHNY vybrané moje, nabídne i „pro všechny".
    pendingDelete?.let { toDelete ->
        val everyone = GroupChatScreenLogic.canDeleteForEveryone(toDelete)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(if (everyone) R.string.chat_delete_choose_body else R.string.chat_delete_body)) },
            confirmButton = {
                if (everyone) {
                    TextButton(onClick = { pendingDelete = null; deleteForEveryone(toDelete) }) {
                        Text(stringResource(R.string.chat_delete_for_everyone))
                    }
                } else {
                    TextButton(onClick = { pendingDelete = null; deleteForMe(toDelete) }) {
                        Text(stringResource(R.string.chat_action_delete))
                    }
                }
            },
            dismissButton = {
                if (everyone) {
                    TextButton(onClick = { pendingDelete = null; deleteForMe(toDelete) }) {
                        Text(stringResource(R.string.chat_action_delete))
                    }
                } else {
                    TextButton(onClick = { pendingDelete = null }) { Text(stringResource(android.R.string.cancel)) }
                }
            }
        )
    }

    // Plný emoji picker pro reakci (otevřený z „+" v paletě) — stejná komponenta jako 1:1.
    emojiPickerFor?.let { targetId ->
        EmojiPickerSheet(
            onPick = { emoji -> react(targetId, emoji); emojiPickerFor = null },
            onDismiss = { emojiPickerFor = null }
        )
    }

    // Fotka přes celou obrazovku (stejný prohlížeč jako 1:1).
    fullscreenImagePath?.let { path ->
        FullscreenImageViewer(path, onDismiss = { fullscreenImagePath = null })
    }
}

/** Akční lišta výběrového režimu — jako 1:1: zavřít, odpovědět, upravit, kopírovat, smazat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSelectionTopBar(
    count: Int,
    canReply: Boolean,
    canEdit: Boolean,
    canCopy: Boolean,
    onClose: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_back))
            }
        },
        title = { Text(count.toString(), style = MaterialTheme.typography.titleLarge) },
        actions = {
            if (canReply) IconButton(onClick = onReply) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.chat_action_reply))
            }
            if (canEdit) IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.chat_action_edit))
            }
            if (canCopy) IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.chat_action_copy))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.chat_action_delete))
            }
        }
    )
}

/**
 * Bublina skupinové zprávy — vizuálně a chováním SHODNÁ s 1:1: asymetrický roh, moje =
 * `accent.bubble`, cizí = `surfaceVariant`, textové fajfky, citace nad obsahem, swipe→odpověď,
 * dvojklik→👍, dlouhý stisk→výběr (+ plovoucí paleta reakcí u jediné vybrané), náhrobek u
 * smazané, „upraveno" u upravené, zvýraznění hledaného. Navíc jméno odesílatele nad příchozí.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupChatMessage,
    senderName: String?,
    myMemberId: String,
    quoted: GroupChatMessage?,
    quotedMissing: Boolean,
    quotedAuthor: String,
    highlighted: Boolean,
    highlightQuery: String,
    selected: Boolean,
    selectionMode: Boolean,
    showReactionPicker: Boolean,
    onReact: (String) -> Unit,
    onMore: () -> Unit,
    onReplySwipe: () -> Unit,
    onQuoteClick: () -> Unit,
    onSelect: () -> Unit,
    onTapInSelection: () -> Unit,
    onDismissReactions: () -> Unit,
    onRetry: () -> Unit,
    onImageClick: () -> Unit,
) {
    val outgoing = message.outgoing
    val deleted = message.deleted
    val accent = LocalDesign.current.accent
    val bubbleColor = when {
        deleted -> MaterialTheme.colorScheme.surfaceVariant
        outgoing -> accent.bubble
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        deleted -> MaterialTheme.colorScheme.onSurfaceVariant
        outgoing -> accent.onBubble
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Barva pruhu/autora citace jako 1:1: uvnitř mojí bubliny `onBubble`, u cizí `primary`.
    val quoteAccent = if (outgoing) accent.onBubble else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp
    )
    val myReaction = message.reactions[myMemberId]

    val haptics = LocalHapticFeedback.current
    val highlightBg by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        label = "groupQuoteHighlight"
    )
    var dragX by remember(message.msgIdHex) { mutableFloatStateOf(0f) }
    var dragging by remember(message.msgIdHex) { mutableStateOf(false) }
    val offsetX by animateFloatAsState(targetValue = dragX, animationSpec = if (dragging) snap() else spring(), label = "groupSwipeReply")
    var passedThreshold by remember(message.msgIdHex) { mutableStateOf(false) }

    val rowBg = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else highlightBg

    Column(modifier = Modifier.fillMaxWidth().background(rowBg).padding(vertical = 2.dp)) {
        if (!outgoing && senderName != null) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 1.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(message.msgIdHex, deleted) {
                    if (deleted) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            if (dragX >= GROUP_SWIPE_REPLY_THRESHOLD_PX) onReplySwipe()
                            passedThreshold = false; dragging = false; dragX = 0f
                        },
                        onDragCancel = { passedThreshold = false; dragging = false; dragX = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        val next = (dragX + dragAmount * 0.6f).coerceIn(0f, GROUP_SWIPE_REPLY_THRESHOLD_PX * 1.3f)
                        if (!passedThreshold && next >= GROUP_SWIPE_REPLY_THRESHOLD_PX) {
                            passedThreshold = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        dragX = next
                    }
                },
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
        ) {
            Box {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(shape)
                        .background(bubbleColor)
                        .combinedClickable(
                            onClick = {
                                when {
                                    selectionMode -> onTapInSelection()
                                    message.status == GroupChatMessage.Status.FAILED && outgoing -> onRetry()
                                    message.kind == GroupChatMessage.Kind.IMAGE && !deleted -> onImageClick()
                                }
                            },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSelect() },
                            onDoubleClick = { if (!selectionMode && !deleted) onReact(if (myReaction == GROUP_DEFAULT_REACTION) "" else GROUP_DEFAULT_REACTION) }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (deleted) {
                        Text(
                            stringResource(R.string.chat_message_deleted),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.85f)
                        )
                    } else {
                        if (quoted != null || quotedMissing) {
                            ChatQuotedBlock(
                                author = quotedAuthor,
                                summary = quoted?.let { groupQuotedSummary(it) } ?: "",
                                missing = quotedMissing,
                                accent = quoteAccent,
                                textColor = textColor,
                                onClick = onQuoteClick
                            )
                        }
                        if (message.kind == GroupChatMessage.Kind.IMAGE) {
                            GroupImage(message.mediaPath)
                        } else {
                            HighlightedText(
                                text = message.text,
                                query = highlightQuery,
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge,
                                format = true
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.editedAt != null && !deleted) {
                            Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                        }
                        Text(
                            GROUP_TIME_FORMAT.format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                        if (outgoing && !deleted) {
                            Text(
                                statusGlyph(message.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == GroupChatMessage.Status.FAILED)
                                    MaterialTheme.colorScheme.error else textColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                if (showReactionPicker && !deleted) {
                    Popup(
                        popupPositionProvider = remember(outgoing) { AboveAnchorPosition(alignEnd = outgoing) },
                        onDismissRequest = onDismissReactions,
                        // NEfokusovatelný (jako 1:1): fokusovatelný Popup je dotykově modální a
                        // spolkl by kliky na akční lištu (Upravit/Smazat) i dlouhý stisk další
                        // bubliny. (Audit 2026-08-03-groups-3, high.) Paleta se schová změnou výběru.
                        properties = PopupProperties(focusable = false)
                    ) {
                        ReactionPicker(
                            emojis = GROUP_REACTIONS,
                            mine = myReaction,
                            onPick = { onReact(if (myReaction == it) "" else it) },
                            onMore = onMore
                        )
                    }
                }
            }
        }
        if (message.status == GroupChatMessage.Status.FAILED && outgoing && !deleted) {
            Text(
                stringResource(R.string.chat_retry_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.End).padding(top = 1.dp, end = 4.dp)
            )
        }
        if (message.reactions.isNotEmpty() && !deleted) {
            ChatReactionChips(message.reactions.values.toList(), outgoing)
        }
    }
}

/** Krátký popis skupinové zprávy pro citaci (u fotky/náhrobku není text) — jako 1:1 `quotedSummary`. */
@Composable
private fun groupQuotedSummary(message: GroupChatMessage): String = when {
    message.deleted -> stringResource(R.string.chat_message_deleted)
    message.kind == GroupChatMessage.Kind.IMAGE -> stringResource(R.string.chat_reply_photo)
    else -> message.text
}

/** Náhled nad vstupním polem, když se píše odpověď (jako 1:1 ReplyComposerPreview). */
@Composable
private fun GroupReplyComposerPreview(message: GroupChatMessage, author: String, onCancel: () -> Unit) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(
                    groupQuotedSummary(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_cancel_reply))
            }
        }
    }
}

/** Pruh nad vstupem v režimu úpravy: tužka, „Upravit" a náhled textu (jako 1:1 EditComposerPreview). */
@Composable
private fun GroupEditComposerPreview(message: GroupChatMessage, onCancel: () -> Unit) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.chat_action_edit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_cancel_reply))
            }
        }
    }
}

/** Textová fajfka jako v 1:1 `StatusGlyph`: SENT „✓", DELIVERED „✓✓", FAILED „!". */
private fun statusGlyph(status: GroupChatMessage.Status): String = when (status) {
    GroupChatMessage.Status.SENDING -> "…"
    GroupChatMessage.Status.SENT -> "✓"
    GroupChatMessage.Status.DELIVERED -> "✓✓"
    GroupChatMessage.Status.FAILED -> "!"
}

/**
 * Systémový řádek (kdo se připojil / byl odebrán) — vycentrovaná pilulka jako
 * oddělovač dne. „Připojili jste se" pro sebe, jinak rozřešené jméno.
 */
@Composable
private fun GroupSystemRow(message: GroupChatMessage, myMemberId: String) {
    val text = when {
        message.kind == GroupChatMessage.Kind.SYSTEM_JOIN && message.senderMemberIdHex == myMemberId ->
            stringResource(R.string.group_system_you_joined)
        message.kind == GroupChatMessage.Kind.SYSTEM_JOIN ->
            stringResource(R.string.group_system_joined, message.text)
        else -> stringResource(R.string.group_system_left, message.text)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** Zobrazení fotky ve skupinové bublině (dekódování mimo hlavní vlákno). */
@Composable
private fun GroupImage(path: String?) {
    val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.sizeIn(maxWidth = 260.dp, maxHeight = 360.dp))
    } else {
        Text(stringResource(R.string.notif_photo), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
