package com.jelenxp.cryptochat.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.ActiveChat
import com.jelenxp.cryptochat.chat.ChatMediaStore
import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.chat.MediaTransfers
import com.jelenxp.cryptochat.chat.RelaySync
import com.jelenxp.cryptochat.chat.TorForegroundService
import com.jelenxp.cryptochat.chat.WireCompat
import com.jelenxp.cryptochat.chat.TorController
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.util.AvatarStore
import com.jelenxp.cryptochat.viewmodel.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Konverzace s jedním kontaktem (chat přes relay). Historie je lokální a
 * šifrovaná ([ChatRepository]); nové zprávy chodí přes „slepou schránku".
 * Obrazovka pravidelně dotazuje server na nové zprávy, dokud je otevřená.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(id: String, navController: NavController, viewModel: ContactsViewModel) {
    val context = LocalContext.current
    val repo = remember { ChatRepository(context) }
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // Kontakt čti ŽIVĚ z ViewModelu, ne jednorázově. Zachycená kopie by po
    // obnově klíče držela ten starý a zprávy by protistrana nerozšifrovala.
    val allContacts by viewModel.contacts.collectAsState()
    val contact = remember(allContacts, id) { allContacts.find { it.id == id } }
    // Historii NEnačítej v kompozici - `getMessages` dešifruje Keystorem a u delší
    // konverzace by to na hlavním vlákně znamenalo zamrznutí až ANR.
    var messages by remember(id) { mutableStateOf(emptyList<ChatMessage>()) }
    LaunchedEffect(id) {
        messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
    }
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    // Vybrané zprávy (dlouhý stisk vybere, klepnutí ve výběru přepíná) a zpráva,
    // na kterou se odpovídá. Drží se podle `id`, ne podle indexu - LazyColumn
    // položky recykluje.
    var selectedIds by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var replyTo by remember(id) { mutableStateOf<ChatMessage?>(null) }
    var pendingDelete by remember(id) { mutableStateOf<List<ChatMessage>>(emptyList()) }

    // Čte SharedPreferences, takže ne při každé rekompozici.
    val relayUrl = remember { settings.getRelayUrl() }
    // Stav kompatibility formátu s protějškem (WireCompat drží Compose stav,
    // takže se banner objeví hned, jak dorazí zpráva z jiné verze).
    val peerCompat = WireCompat.peerState(context, id)
    val hasKey = contact?.keyBase64 != null
    val canChat = hasKey && relayUrl.isNotBlank()

    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // ChatScreen ZÁMĚRNĚ NEPOLLUJE. Relay je dead-drop - GET zprávu smaže, takže
    // dva nezávislí příjemci téže schránky by o každou zprávu závodili: jednou by
    // ji sebrala obrazovka, podruhé service (a ta by pak poslala notifikaci ke
    // konverzaci, kterou má uživatel právě otevřenou). Jediným příjemcem je proto
    // foreground service; obrazovka je čistá prezentace nad historií.
    //
    // `ActiveChat` už neřídí, kdo pollovává - říká jen „tuhle konverzaci uživatel
    // právě čte", takže se pro ni potlačí notifikace.
    LaunchedEffect(id, canChat) {
        if (contact == null || !canChat) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ActiveChat.currentId = id
            withContext(Dispatchers.IO) { repo.markRead(id) }  // otevřená konverzace = přečteno
            // Pro .onion relay nastartuj zabudovaný Tor (idempotentní) a popožeň
            // službu, ať pro čerstvě spárovaný kontakt nečekáme na hlídač.
            // ensureStarted dělá diskovou IO (getDir + stavba runtime) - mimo main.
            if (relayUrl.contains(".onion")) withContext(Dispatchers.IO) { TorController.ensureStarted(context) }
            TorForegroundService.ensureRunning(context)
            try {
                // Přenačti historii při KAŽDÉM návratu do popředí. `changes` je
                // SharedFlow bez replaye, takže zprávy doručené, když obrazovka
                // neposlouchala (uživatel byl jinde v appce), by se jinak
                // neobjevily až do příchodu další zprávy.
                messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
                ChatRepository.changes
                    .filter { it == id }
                    .collect {
                        messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
                        withContext(Dispatchers.IO) { repo.markRead(id) }
                    }
            } finally {
                if (ActiveChat.currentId == id) ActiveChat.currentId = null
            }
        }
    }

    // Odrolování na poslední zprávu. Poprvé (otevření chatu) SKOKEM, ať to při
    // příchodu na obrazovku neprobliká animovaným rolováním odshora; další nové
    // zprávy pak plynule animovaně.
    var initialScrollDone by remember(id) { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!initialScrollDone) {
                listState.scrollToItem(messages.size - 1)
                initialScrollDone = true
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty() || contact == null) return
        input = ""
        // Odkaz vytáhni TEĎ a náhled zavři - kdyby se to dělalo až v korutině,
        // uživatel by mezitím mohl odpověď zrušit a zpráva by odešla s odkazem.
        val replyRef = replyTo?.wireRef
        replyTo = null
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                RelaySync.enqueue(context, contact, text, replyRef)
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, msg) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    /** Přepne naši reakci: stejné emoji podruhé ji zruší. */
    val reactionsUnsupported = stringResource(R.string.chat_reactions_unsupported)
    fun react(message: ChatMessage, emoji: String) {
        val ref = message.wireRef ?: return
        if (contact == null) return
        selectedIds = emptySet()
        scope.launch {
            val next = ChatScreenLogic.toggledReaction(
                message.reactionOf(ChatMessage.REACTOR_ME), emoji
            )
            val result = withContext(Dispatchers.IO) {
                RelaySync.sendReaction(context, contact, ref, next)
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
            // Reakce se neuloží ani lokálně, když ji nejde doručit - tak ať
            // uživatel nekouká na tlačítko, které nic neudělalo.
            if (result == RelaySync.ReactionSend.PEER_UNSUPPORTED) {
                Toast.makeText(context, reactionsUnsupported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteForMe(toDelete: List<ChatMessage>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                toDelete.forEach { repo.deleteMessage(context, id, it.id) }
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    fun retry(message: ChatMessage) {
        // Opakovat lze jen ODESLÁNÍ. Příchozí zpráva se přes `deliver` posílat
        // nemá - jen by se nesmyslně přepnula na SENDING a zpátky na FAILED.
        if (contact == null || !message.outgoing) return
        scope.launch {
            withContext(Dispatchers.IO) { repo.updateStatus(id, message.id, ChatMessage.Status.SENDING) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, message) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    // --- Posílání fotek ---
    val imageFailed = stringResource(R.string.chat_image_failed)
    fun sendImage(uri: Uri) {
        if (contact == null) return
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { ChatMediaStore.compress(context, uri) }
            if (jpeg == null) {
                Toast.makeText(context, imageFailed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val msg = withContext(Dispatchers.IO) { RelaySync.enqueueImage(context, contact, jpeg) }
            if (msg == null) {
                Toast.makeText(context, imageFailed, Toast.LENGTH_LONG).show()
                return@launch
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, msg) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) sendImage(uri) }

    // rememberSaveable: při focení může systém proces zabít (málo RAM). Bez
    // uložení by se po návratu cameraUri ztratilo a pořízená fotka by se tiše
    // zahodila. Uri je Parcelable, takže se uloží samo.
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> val u = cameraUri; if (ok && u != null) sendImage(u) }
    fun launchCamera() {
        val u = AvatarStore.newCameraOutputUri(context) ?: return
        cameraUri = u
        cameraLauncher.launch(u)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCamera() }
    fun onCameraClick() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) launchCamera() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }
    // --- Posílání souborů (video, dokumenty) po kouscích ---
    val fileFailed = stringResource(R.string.chat_file_failed)
    fun sendFile(uri: Uri) {
        if (contact == null) return
        scope.launch {
            val msg = withContext(Dispatchers.IO) { RelaySync.enqueueFile(context, contact, uri) }
            if (msg == null) {
                Toast.makeText(context, fileFailed, Toast.LENGTH_LONG).show()
                return@launch
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, msg) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) sendFile(uri) }

    var attachMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copiedLabel = stringResource(R.string.chat_copied)

    // Index zpráv podle sdíleného odkazu - pro rychlé dohledání citace.
    val byWireRef = remember(messages) { ChatScreenLogic.wireRefIndex(messages) }

    // Zpráva mohla mezitím zmizet (smazal ji uživatel, nebo přenačtení historie).
    // Bez tohohle by zůstal viset výběrový panel bez jediné akce, případně by se
    // odpovídalo na zprávu, která už není. Rozhodnutí je v ChatScreenLogic, ať
    // jde otestovat (nález v1.2-23).
    LaunchedEffect(messages) {
        selectedIds = ChatScreenLogic.survivingIds(messages, selectedIds)
        replyTo = ChatScreenLogic.survivingReply(messages, replyTo)
    }

    // Systémové zpět nejdřív zavře výběr / rozepsanou odpověď, teprve pak
    // opustí konverzaci - jinak by uživatel omylem vyskočil z chatu.
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    BackHandler(enabled = selectedIds.isEmpty() && replyTo != null) { replyTo = null }

    Scaffold(
        topBar = {
            if (selectedIds.isNotEmpty()) {
                val selectedMsgs = messages.filter { it.id in selectedIds }
                val single = selectedMsgs.singleOrNull()
                val copyable = ChatScreenLogic.copyText(messages, selectedIds)
                TopAppBar(
                    title = { Text(stringResource(R.string.chat_selection_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.content_desc_clear_selection)
                            )
                        }
                    },
                    actions = {
                        // Odpověď a reakce dávají smysl jen u JEDNÉ zprávy.
                        if (single != null && canChat && single.wireRef != null) {
                            IconButton(onClick = { replyTo = single; selectedIds = emptySet() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = stringResource(R.string.chat_action_reply)
                                )
                            }
                        }
                        // Kopírování a mazání jdou i pro víc zpráv naráz.
                        if (copyable.isNotBlank()) {
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(copyable))
                                selectedIds = emptySet()
                                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.chat_action_copy)
                                )
                            }
                        }
                        IconButton(onClick = {
                            pendingDelete = selectedMsgs
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.chat_action_delete)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            } else {
            TopAppBar(
                title = {
                    // Klepnutí na avatar/jméno otevře profil kontaktu (detail + klíč).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = contact != null) {
                                navController.navigate("user_detail/$id")
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        if (contact != null) {
                            ContactAvatar(name = contact.name, avatarPath = contact.avatarPath, size = 32.dp)
                        }
                        Text(contact?.name ?: stringResource(R.string.chat_title_fallback), maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_detail)) },
                            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                            onClick = { menuOpen = false; navController.navigate("user_detail/$id") }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_server)) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; navController.navigate("relay_settings") }
                        )
                    }
                }
            )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Upozornění, když chat ještě nejde používat.
            if (!hasKey) {
                ChatNotice(stringResource(R.string.chat_notice_no_key))
            } else if (relayUrl.isBlank()) {
                ChatNotice(stringResource(R.string.chat_notice_no_server))
            } else when (peerCompat) {
                // Rozdílný formát obálky: bez tohohle by zprávy jen tiše mizely.
                // MAJOR = chat nefunguje, MINOR = funguje, jen chybí novinka.
                WireCompat.Peer.MAJOR_OUTDATED ->
                    ChatNotice(stringResource(R.string.chat_peer_major_outdated))
                WireCompat.Peer.MAJOR_NEWER ->
                    ChatNotice(stringResource(R.string.chat_peer_major_newer))
                WireCompat.Peer.MINOR_OUTDATED ->
                    ChatNotice(stringResource(R.string.chat_peer_minor_outdated))
                WireCompat.Peer.MINOR_NEWER ->
                    ChatNotice(stringResource(R.string.chat_peer_minor_newer))
                WireCompat.Peer.OK -> {}
            }

            if (messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        // Citovaná zpráva se hledá v UŽ NAČTENÉM seznamu - do
                        // kompozice nesmí žádné čtení historie (Keystore = ANR).
                        // Přes mapu, ne lineárním hledáním: u dlouhé konverzace
                        // by se pro každou viditelnou bublinu procházelo všechno.
                        val quote = ChatScreenLogic.resolveQuote(m, byWireRef)
                        MessageRow(
                            message = m,
                            quoted = quote.message,
                            quotedMissing = quote.missing,
                            peerName = contact?.name.orEmpty(),
                            selected = m.id in selectedIds,
                            // Pruh emoji jen když je vybraná JEN tahle jedna zpráva.
                            showReactionPicker = selectedIds == setOf(m.id),
                            canReact = canChat && m.wireRef != null,
                            selectionMode = selectedIds.isNotEmpty(),
                            onSelect = { selectedIds = selectedIds + m.id },
                            onTapInSelection = { selectedIds = ChatScreenLogic.toggleSelection(selectedIds, m.id) },
                            onReact = { emoji -> react(m, emoji) },
                            onReplySwipe = { if (canChat && m.wireRef != null) replyTo = m },
                            onRetry = { retry(m) }
                        )
                    }
                }
            }

            // Náhled zprávy, na kterou se odpovídá.
            replyTo?.let { target ->
                ReplyComposerPreview(
                    message = target,
                    peerName = contact?.name.orEmpty(),
                    onCancel = { replyTo = null }
                )
            }

            // Vstupní řádek.
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box {
                        IconButton(onClick = { attachMenu = true }, enabled = canChat) {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.content_desc_attach)
                            )
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_camera)) },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                                onClick = { attachMenu = false; onCameraClick() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_gallery)) },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                                onClick = {
                                    attachMenu = false
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_file)) },
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                onClick = { attachMenu = false; fileLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                        enabled = canChat,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f)
                    )
                    FilledIconButton(
                        onClick = { sendCurrent() },
                        enabled = canChat && input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.content_desc_send))
                    }
                }
            }
        }
    }

    // Mazání se potvrzuje: je nevratné a u přijaté zprávy ji relay už smazal,
    // takže se nedá získat zpátky.
    if (pendingDelete.isNotEmpty()) {
        val toDelete = pendingDelete
        val deletedLabel = stringResource(R.string.chat_deleted)
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(R.string.chat_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = emptyList()
                    deleteForMe(toDelete)
                    Toast.makeText(context, deletedLabel, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/** Emoji nabízená na dlouhý stisk. Protokol jich unese libovolně, UI zatím tyhle. */
private val QUICK_REACTIONS = ChatScreenLogic.QUICK_REACTIONS

/** Jak daleko se musí bublina odtáhnout, aby se odpověď spustila. */
private const val SWIPE_REPLY_THRESHOLD_PX = 180f

/**
 * Jeden řádek konverzace: zvýraznění při výběru, pruh emoji, citace nad
 * bublinou, reakce pod ní a tažení zleva doprava jako zkratka pro odpověď.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    quoted: ChatMessage?,
    quotedMissing: Boolean,
    peerName: String,
    selected: Boolean,
    showReactionPicker: Boolean,
    canReact: Boolean,
    selectionMode: Boolean,
    onSelect: () -> Unit,
    onTapInSelection: () -> Unit,
    onReact: (String) -> Unit,
    onReplySwipe: () -> Unit,
    onRetry: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // Během tahu se posun mění přímo (bez animace), po puštění se animuje zpět.
    // Animatable + snapTo v korutině by znamenalo spustit korutinu při každé
    // události prstu, tedy desítky za sekundu.
    var dragX by remember(message.id) { mutableFloatStateOf(0f) }
    var dragging by remember(message.id) { mutableStateOf(false) }
    val offsetX by animateFloatAsState(
        targetValue = dragX,
        animationSpec = if (dragging) snap() else spring(),
        label = "swipeReply"
    )
    // Aby haptika cvakla jen jednou při překročení prahu, ne při každém pixelu.
    var passedThreshold by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ) else Modifier
            )
            .padding(vertical = 2.dp)
    ) {
        if (showReactionPicker && canReact) {
            ReactionPicker(
                mine = message.reactionOf(ChatMessage.REACTOR_ME),
                onPick = onReact
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(message.id, canReact) {
                    if (!canReact) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            if (dragX >= SWIPE_REPLY_THRESHOLD_PX) onReplySwipe()
                            passedThreshold = false
                            dragging = false
                            dragX = 0f
                        },
                        onDragCancel = {
                            passedThreshold = false
                            dragging = false
                            dragX = 0f
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        // Jen doprava a s odporem - tažení se ke konci zpomaluje,
                        // ať gesto nepůsobí, že bublinu odtáhneš pryč.
                        val next = (dragX + dragAmount * 0.6f)
                            .coerceIn(0f, SWIPE_REPLY_THRESHOLD_PX * 1.3f)
                        if (!passedThreshold && next >= SWIPE_REPLY_THRESHOLD_PX) {
                            passedThreshold = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        dragX = next
                    }
                }
        ) {
            MessageBubble(
                message = message,
                quoted = quoted,
                quotedMissing = quotedMissing,
                peerName = peerName,
                onRetry = onRetry,
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect()
                },
                // Ve výběrovém režimu klepnutí přidává/odebírá zprávu z výběru.
                onTap = if (selectionMode) onTapInSelection else null
            )
        }
    }
}

/** Vodorovný pruh rychlých reakcí. Vybraná je zvýrazněná. */
@Composable
private fun ReactionPicker(mine: String?, onPick: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                val isMine = mine == emoji
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .then(
                            if (isMine) Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                            ) else Modifier
                        )
                        .clickable { onPick(emoji) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** Reakce přilepené pod bublinou. Stejné emoji od obou se sloučí a přičte počet. */
@Composable
private fun ReactionChips(reactions: Map<String, ChatMessage.Reaction>, outgoing: Boolean) {
    if (reactions.isEmpty()) return
    val grouped = reactions.values.groupBy { it.emoji }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                grouped.forEach { (emoji, list) ->
                    Text(
                        if (list.size > 1) "$emoji ${list.size}" else emoji,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

/** Krátký popis zprávy pro citaci (u fotky/souboru není text). */
@Composable
private fun quotedSummary(message: ChatMessage): String = when (message.kind) {
    ChatMessage.Kind.IMAGE -> stringResource(R.string.chat_reply_photo)
    ChatMessage.Kind.FILE -> message.text.ifBlank { stringResource(R.string.chat_reply_file) }
    else -> message.text
}

/** Citace uvnitř bubliny - barevný pruh vlevo, jméno a náhled textu. */
@Composable
private fun QuotedBlock(
    quoted: ChatMessage?,
    missing: Boolean,
    peerName: String,
    accent: Color,
    textColor: Color
) {
    if (quoted == null && !missing) return
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(textColor.copy(alpha = 0.10f))
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (quoted != null) {
                Text(
                    if (quoted.outgoing) stringResource(R.string.chat_reply_you) else peerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1
                )
                Text(
                    quotedSummary(quoted),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    stringResource(R.string.chat_reply_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
    }
}

/** Náhled nad vstupním polem, když se píše odpověď. */
@Composable
private fun ReplyComposerPreview(
    message: ChatMessage,
    peerName: String,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    if (message.outgoing) stringResource(R.string.chat_reply_you) else peerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Text(
                    quotedSummary(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.content_desc_cancel_reply)
                )
            }
        }
    }
}

@Composable
private fun ChatNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    quoted: ChatMessage? = null,
    quotedMissing: Boolean = false,
    peerName: String = "",
    onRetry: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    val outgoing = message.outgoing
    val bubbleColor = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp
    )

    Column {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                // Dlouhý stisk otevírá výběr. Klepnutí: ve výběru přepíná
                // označení ([onTap]), jinak jen opakuje ODCHOZÍ neúspěšnou zprávu
                // (příchozí se opakovat nedá, viz ChatScreen.retry).
                .then(
                    if (onLongPress != null) Modifier.combinedClickable(
                        onLongClick = onLongPress,
                        onClick = {
                            when {
                                onTap != null -> onTap()
                                message.status == ChatMessage.Status.FAILED && outgoing -> onRetry()
                            }
                        }
                    ) else if (message.status == ChatMessage.Status.FAILED && outgoing)
                        Modifier.clickable { onRetry() } else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            QuotedBlock(
                quoted = quoted,
                missing = quotedMissing,
                peerName = peerName,
                accent = accent,
                textColor = textColor
            )
            when (message.kind) {
                ChatMessage.Kind.IMAGE -> ChatImage(path = message.mediaPath)
                ChatMessage.Kind.FILE -> FileBubble(message = message, textColor = textColor)
                else -> Text(message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    TIME_FORMAT.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                if (outgoing) StatusGlyph(message.status, textColor)
            }
            if (message.status == ChatMessage.Status.FAILED) {
                Text(
                    // Odchozí = „klepni pro opakování"; příchozí soubor se
                    // opakovat nedá, tak jen „přijetí selhalo".
                    stringResource(
                        if (outgoing) R.string.chat_retry_hint else R.string.chat_receive_failed
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    ReactionChips(reactions = message.visibleReactions, outgoing = outgoing)
    }
}

/** Fotka v bublině - načte se ze souboru mimo hlavní vlákno a zobrazí dekódovaná. */
@Composable
private fun ChatImage(path: String?) {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    // `failed` začíná true, když médium vůbec není (neuložilo se / import zálohy
    // bez fotky) - jinak by přijatá fotka visela jako prázdná bublina.
    var failed by remember(path) { mutableStateOf(path == null) }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { ChatMediaStore.decodeForDisplay(path)?.asImageBitmap() }
        bmp = decoded
        failed = decoded == null   // dekódování selhalo -> ukaž chybu, ne věčný spinner
    }
    val b = bmp
    when {
        b != null -> {
            val ratio = if (b.height > 0) b.width.toFloat() / b.height else 1f
            Image(
                bitmap = b,
                contentDescription = stringResource(R.string.content_desc_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .aspectRatio(ratio.coerceIn(0.5f, 2.2f))
                    .clip(RoundedCornerShape(10.dp))
            )
        }
        failed -> {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.chat_image_unavailable),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Bublina se souborem (video, dokument…): ikona podle typu, název a průběh
 * přenosu. Když je soubor kompletní, klepnutím se otevře v systémové aplikaci.
 */
@Composable
private fun FileBubble(message: ChatMessage, textColor: Color) {
    val context = LocalContext.current
    val progress = MediaTransfers.progress[message.id]
    val path = message.mediaPath
    val ready = path != null && message.status != ChatMessage.Status.RECEIVING

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .widthIn(max = 240.dp)
            .then(
                if (ready) Modifier.clickable {
                    ChatMediaStore.openFile(context, path!!, message.mimeType)
                } else Modifier
            )
    ) {
        Icon(
            imageVector = fileIconFor(message.mimeType),
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(30.dp)
        )
        Column {
            Text(
                text = message.text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            } else if (message.status == ChatMessage.Status.RECEIVING) {
                Text(
                    stringResource(R.string.chat_file_receiving),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** Ikona podle typu souboru. */
private fun fileIconFor(mimeType: String?): ImageVector = when {
    mimeType == null -> Icons.Default.InsertDriveFile
    mimeType.startsWith("video/") -> Icons.Default.Movie
    mimeType.startsWith("audio/") -> Icons.Default.MusicNote
    else -> Icons.Default.InsertDriveFile
}

@Composable
private fun StatusGlyph(status: ChatMessage.Status, tint: Color) {
    when (status) {
        ChatMessage.Status.SENDING -> Icon(
            Icons.Default.Schedule, contentDescription = stringResource(R.string.content_desc_sending),
            modifier = Modifier.size(14.dp), tint = tint.copy(alpha = 0.7f)
        )
        ChatMessage.Status.SENT -> Text("✓", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f))
        ChatMessage.Status.FAILED -> Icon(
            Icons.Default.ErrorOutline, contentDescription = stringResource(R.string.content_desc_not_delivered),
            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error
        )
        // Přijaté zprávy (i rozpracovaný příjem souboru) stavovou ikonu nemají -
        // ta je jen u odchozích.
        ChatMessage.Status.RECEIVED, ChatMessage.Status.RECEIVING -> {}
    }
}
