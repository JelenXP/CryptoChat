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
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.chat.ActiveChat
import com.jelenxp.cryptochat.chat.ChatNotifications
import com.jelenxp.cryptochat.chat.ChatMediaStore
import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.chat.ChatRepository
import com.jelenxp.cryptochat.chat.DraftStore
import com.jelenxp.cryptochat.crypto.CryptoManager
import com.jelenxp.cryptochat.data.TrustState
import com.jelenxp.cryptochat.data.TrustStore
import com.jelenxp.cryptochat.chat.MediaTransfers
import com.jelenxp.cryptochat.chat.MuteStore
import com.jelenxp.cryptochat.chat.RelaySync
import com.jelenxp.cryptochat.chat.isMutedAt
import com.jelenxp.cryptochat.ui.components.MuteDurationDialog
import com.jelenxp.cryptochat.chat.TorForegroundService
import com.jelenxp.cryptochat.chat.WireCompat
import com.jelenxp.cryptochat.chat.TorController
import com.jelenxp.cryptochat.data.SettingsRepository
import com.jelenxp.cryptochat.ui.components.ContactAvatar
import com.jelenxp.cryptochat.ui.emoji.EmojiPickerSheet
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
    // Počet nepřečtených při OTEVŘENÍ (pro čáru „Nové zprávy"). Zachytit DŘÍV, než
    // RESUMED efekt níž zavolá markRead a vynuluje ho; drží se po dobu otevření
    // (remember(id)). getUnreadCount je levný int z prefs, ne Keystore.
    var unreadAtOpen by remember(id) { mutableStateOf(0) }
    LaunchedEffect(id) {
        unreadAtOpen = withContext(Dispatchers.IO) { repo.getUnreadCount(id) }
        messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
    }
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    // Trvalý rozepsaný text (draft): přežije odchod z konverzace i restart.
    // Načti ho JEDNOU při otevření (mimo main - dešifruje se Keystorem). `draftLoaded`
    // gate zabrání, aby ukládací efekt níž smazal draft dřív, než ho vůbec načteme.
    var draftLoaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val draft = withContext(Dispatchers.IO) { DraftStore(context).get(id) }
        if (draft.isNotEmpty()) input = draft
        draftLoaded = true
    }
    // Ukládej rozepsaný text debounced (400 ms po posledním úhozu). Prázdný text
    // draft smaže (po odeslání input="" → smaz). Nikdy před dokončením načtení.
    LaunchedEffect(input, id, draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        delay(400)
        withContext(Dispatchers.IO) { DraftStore(context).set(id, input) }
    }

    // Hledání v konverzaci: horní lišta se přepne na vyhledávací pole a seznam
    // ukazuje jen shodné zprávy. Filtr je čistá funkce v ChatScreenLogic.
    var searchMode by remember(id) { mutableStateOf(false) }
    var searchQuery by remember(id) { mutableStateOf("") }

    // Ztlumení oznámení tohoto kontaktu. `mutedUntil` čti mimo kompozici
    // (SharedPreferences); aktualizuje se lokálně při (od)ztlumení.
    var mutedUntil by remember(id) { mutableStateOf<Long?>(null) }
    var showMuteDialog by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        mutedUntil = withContext(Dispatchers.IO) { MuteStore.mutedUntil(context, id) }
    }
    // Časované ztlumení: `muted` se počítá z `mutedUntil` v kompozici, takže by se
    // po vypršení samo nepřekreslilo (indikátor by dál svítil, i když notifikace
    // už zase chodí - gate čte čas čerstvě). Naplánuj srovnání na okamžik vypršení.
    // INDEFINITE ani null nic neplánují.
    LaunchedEffect(mutedUntil) {
        val until = mutedUntil ?: return@LaunchedEffect
        if (until == MuteStore.INDEFINITE) return@LaunchedEffect
        val remaining = until - System.currentTimeMillis()
        if (remaining > 0) {
            delay(remaining)
            withContext(Dispatchers.IO) { MuteStore.unmute(context, id) }
            mutedUntil = null
        }
    }
    val muted = isMutedAt(mutedUntil, System.currentTimeMillis())
    // Seznam k ZOBRAZENÍ: v hledání filtrovaný, jinak celá historie. Zdroj dat
    // (`messages`) i index citací zůstávají nad plnou historií - jen se jinak kreslí.
    val visibleMessages = remember(messages, searchMode, searchQuery) {
        if (searchMode) ChatScreenLogic.filterMessages(messages, searchQuery) else messages
    }
    // Řádky seznamu: mimo hledání s oddělovači dní a čárou „Nové zprávy", v hledání
    // ploché (oddělovače by u filtrovaných výsledků napříč dny nedávaly smysl).
    // `dayOf` počítá den v LOKÁLNÍ zóně; sama buildRows je čistá a testovaná.
    val chatZone = remember { java.time.ZoneId.systemDefault() }
    val rows = remember(visibleMessages, searchMode, unreadAtOpen) {
        if (searchMode) visibleMessages.map { ChatScreenLogic.ChatRow.Msg(it) }
        else ChatScreenLogic.buildRows(visibleMessages, unreadAtOpen) { ts ->
            java.time.Instant.ofEpochMilli(ts).atZone(chatZone).toLocalDate().toEpochDay()
        }
    }

    // Vybrané zprávy (dlouhý stisk vybere, klepnutí ve výběru přepíná) a zpráva,
    // na kterou se odpovídá. Drží se podle `id`, ne podle indexu - LazyColumn
    // položky recykluje.
    var selectedIds by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var replyTo by remember(id) { mutableStateOf<ChatMessage?>(null) }
    var pendingDelete by remember(id) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    // Zpráva, kterou právě upravuji (vstupní pole nese její nový text). null = píšu novou.
    var editing by remember(id) { mutableStateOf<ChatMessage?>(null) }
    // Zpráva, pro kterou je otevřený plný emoji picker (z „+" v paletě reakcí).
    var emojiPickerFor by remember(id) { mutableStateOf<ChatMessage?>(null) }
    // Zpráva krátce zvýrazněná po skoku z citace (`onQuoteClick`).
    var highlightedId by remember(id) { mutableStateOf<String?>(null) }

    // Čte SharedPreferences, takže ne při každé rekompozici.
    val relayUrl = remember { settings.getRelayUrl() }
    // Stav kompatibility formátu s protějškem (WireCompat drží Compose stav,
    // takže se banner objeví hned, jak dorazí zpráva z jiné verze).
    val peerCompat = WireCompat.peerState(context, id)
    // Trust pinning: změnil se ověřený otisk klíče? Pin je na STATICKÝ klíč M
    // (otisk se re-keyem nemění), takže „změněno" = skutečná výměna klíče. Čte se
    // mimo main (Keystore) a přepočítá při změně klíče kontaktu (obnova apod.).
    var trustChanged by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, contact?.keyBase64) {
        val k = contact?.keyBase64
        trustChanged = if (k == null) false else withContext(Dispatchers.IO) {
            val stored = TrustStore(context).verifiedFingerprint(id)
            TrustState.evaluate(stored, CryptoManager.fingerprint(k)) == TrustState.Level.CHANGED
        }
    }
    val hasKey = contact?.keyBase64 != null
    val canChat = hasKey && relayUrl.isNotBlank()

    // Předehřátí ODESÍLACÍHO Tor okruhu: jakmile uživatel začne psát (vstup přestane
    // být prázdný), postav dopředu okruh pro odesílací schránku, ať první PUT nečeká
    // na studenou stavbu. Jen při přechodu prázdný→neprázdný (derivedStateOf drží
    // boolean, LaunchedEffect se restartuje jen na jeho změně), mimo main, best-effort.
    // Neposouvá ratchet (prewarmSend čte jen sendEpoch).
    val inputNotBlank by remember { derivedStateOf { input.isNotBlank() } }
    LaunchedEffect(inputNotBlank, canChat) {
        if (inputNotBlank && canChat && contact != null) {
            withContext(Dispatchers.IO) { RelaySync.prewarmSend(context, contact) }
        }
    }

    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Držení konverzace „u dna" (řeší tři UI bugy: fotka neotevře úplně dole,
    // psaní odscrollované zprávy skočí na konec, reakce zaskočí konec pod lištu) ---
    // Rezerva na zaokrouhlení: do téhle vzdálenosti od dna se pořád počítáme za „u dna".
    val bottomTolerancePx = with(LocalDensity.current) { 12.dp.roundToPx() }
    // Je uživatel u dna? Rozhodovací výpočet je vytažený do ChatScreenLogic.isAtBottom
    // (otestovatelný bez Androidu); tady jen dodáme čísla z LazyListState.
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            ChatScreenLogic.isAtBottom(
                lastVisibleIndex = last?.index,
                lastVisibleItemEnd = last?.let { it.offset + it.size } ?: 0,
                totalItems = info.totalItemsCount,
                viewportEnd = info.viewportEndOffset,
                tolerancePx = bottomTolerancePx
            )
        }
    }
    // „Chceme být u dna": drží se, dokud uživatel neodroluje pryč. Přehodnocuje se AŽ
    // po dojetí scrollu, NE při změně výšky viewportu od klávesnice - jinak by
    // otevření klávesnice u dna flag shodilo a poslední zpráva by zůstala za lištou.
    var stickToBottom by remember(id) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }               // jen když scroll DOJEL (uživatelský i programový)
            .collect { stickToBottom = atBottom }
    }
    // Počet nových zpráv od chvíle, co uživatel odscrolloval z konce - odznak na
    // tlačítku „skočit dolů". Baseline se srovná s aktuální velikostí VŽDY, když
    // je uživatel u dna (viděl vše); mimo dno se drží, takže ho nové zprávy
    // přerostou. Odchozí zpráva rovnou vrací na dno (stickToBottom), takže se sem
    // prakticky počítají jen příchozí.
    var bottomAnchorSize by remember(id) { mutableStateOf(0) }
    LaunchedEffect(atBottom, visibleMessages.size) {
        if (atBottom) bottomAnchorSize = visibleMessages.size
    }
    val newSinceScroll = (visibleMessages.size - bottomAnchorSize).coerceAtLeast(0)

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
            // Uživatel obsah právě čte - shoď případnou notifikaci tohoto kontaktu
            // z lišty (jinak by tam visela, dokud na ni neklepne).
            ChatNotifications.cancelMessage(context, id)
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
    // zprávy pak plynule animovaně. Offset SCROLL_BOTTOM_OFFSET tlačí na SKUTEČNÉ
    // dno - bez něj by se u vysoké poslední zprávy skončilo „skoro na konci".
    var initialScrollDone by remember(id) { mutableStateOf(false) }
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) {
            if (!initialScrollDone) {
                listState.scrollToItem(rows.lastIndex, SCROLL_BOTTOM_OFFSET)
                initialScrollDone = true
            } else {
                listState.animateScrollToItem(rows.lastIndex, SCROLL_BOTTOM_OFFSET)
            }
            // Nový příspěvek (odeslaný i přijatý) = jdeme k dnu a chceme tam zůstat.
            // Zachovává dosavadní chování „po odeslání to odscrolluje dopředu, i když
            // jsem byl vzadu v konverzaci".
            stickToBottom = true
        }
    }

    // Jednotné držení u dna. Když tam být MÁME (stickToBottom), ale nejsme (obsah
    // povyrostl dekódovanou fotkou / přidanou reakcí, nebo se zmenšil viewport od
    // klávesnice / náhledu odpovědi / víc řádků vstupu), přirolujeme na skutečné dno.
    // Když uživatel odscrolloval do historie (stickToBottom == false), NEVYSKAKUJEME
    // na konec - to byl přesně ten otravný skok při psaní odscrollované zprávy.
    // `visibleMessages` je obyčejný `remember` (ne State delegát), takže by ho
    // dlouhožijící korutina zachytila hodnotou z PRVNÍ kompozice - a to je prázdný
    // seznam (historie se načítá až async). Efekt klíčovaný na stabilní `listState`
    // se nerestartuje, takže by guard `isNotEmpty()` zůstal navždy false a
    // přerolování dolů by se nikdy nespustilo. `rememberUpdatedState` drží stabilní
    // referenci, jejíž `.value` se aktualizuje každou kompozicí (čte se živě).
    val liveRows by rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        snapshotFlow { Triple(stickToBottom, atBottom, listState.isScrollInProgress) }
            .collect { (stick, bottom, scrolling) ->
                val rs = liveRows
                if (stick && !bottom && !scrolling && rs.isNotEmpty()) {
                    listState.scrollToItem(rs.lastIndex, SCROLL_BOTTOM_OFFSET)
                }
            }
    }

    // Skok na citovanou zprávu (klik na citaci): doroluj na její řádek a krátce ji
    // zvýrazni. Index se hledá v UŽ SESTAVENÝCH `rows`, žádné čtení historie.
    fun jumpToMessage(target: ChatMessage) {
        val idx = rows.indexOfFirst {
            it is ChatScreenLogic.ChatRow.Msg && it.message.id == target.id
        }
        if (idx < 0) return
        scope.launch {
            listState.animateScrollToItem(idx)
            highlightedId = target.id
            delay(1500)
            if (highlightedId == target.id) highlightedId = null
        }
    }

    val messageFailed = stringResource(R.string.chat_message_failed)
    val editTooLong = stringResource(R.string.chat_edit_too_long)
    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty() || contact == null) return
        // Režim úpravy: přepiš text existující zprávy místo poslání nové.
        val editTarget = editing
        if (editTarget != null) {
            val ref = editTarget.wireRef ?: run { editing = null; input = ""; return }
            input = ""
            editing = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    RelaySync.sendEdit(context, contact, ref, text)
                }
                messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
                when (result) {
                    // Neuloženo - vrať text i režim úpravy, ať jde zkusit znovu / zkrátit.
                    RelaySync.MutationSend.TOO_LONG -> {
                        input = text; editing = editTarget
                        Toast.makeText(context, editTooLong, Toast.LENGTH_LONG).show()
                    }
                    RelaySync.MutationSend.FAILED -> {
                        input = text; editing = editTarget
                        Toast.makeText(context, messageFailed, Toast.LENGTH_LONG).show()
                    }
                    RelaySync.MutationSend.SENT -> Unit
                }
            }
            return
        }
        input = ""
        // Odkaz vytáhni TEĎ a náhled zavři - kdyby se to dělalo až v korutině,
        // uživatel by mezitím mohl odpověď zrušit a zpráva by odešla s odkazem.
        val replyRef = replyTo?.wireRef
        // Snapshot i pro obnovu při selhání (nález round-3-b-1): jinak by se vrátil
        // jen text, ale citovaná zpráva by se ztratila.
        val replySnapshot = replyTo
        replyTo = null
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                RelaySync.enqueue(context, contact, text, replyRef)
            }
            // Uložení do historie selhalo → NEODESÍLAT (jinak by zpráva odešla, ale
            // u nás v historii nebyla). Vrať text i citaci do pole, ať jde zkusit znovu.
            if (msg == null) {
                input = text
                replyTo = replySnapshot
                Toast.makeText(context, messageFailed, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Odesláno → draft hned zahoď (debounced efekt by ho stejně smazal, tohle
            // je jen okamžité, ať se sem po zavření appky do 400 ms nevrátí).
            withContext(Dispatchers.IO) { DraftStore(context).clear(id) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
            withContext(Dispatchers.IO) { RelaySync.deliver(context, contact, msg) }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    val reactionsUnsupported = stringResource(R.string.chat_reactions_unsupported)
    /** Nastaví/zruší naši reakci (`next == null` = zrušit) a pošle ji. */
    fun applyReaction(message: ChatMessage, next: String?) {
        val ref = message.wireRef ?: return
        if (contact == null) return
        selectedIds = emptySet()
        scope.launch {
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

    /** Z palety: stejné emoji podruhé ji zruší, jiné ji nahradí. */
    fun react(message: ChatMessage, emoji: String) =
        applyReaction(message, ChatScreenLogic.toggledReaction(message.reactionOf(ChatMessage.REACTOR_ME), emoji))

    /** Dvojklep: nemám-li reakci, přidá 👍; mám-li jakoukoli, sundá ji. */
    fun doubleTapReact(message: ChatMessage) =
        applyReaction(message, ChatScreenLogic.doubleTapReaction(message.reactionOf(ChatMessage.REACTOR_ME)))

    fun deleteForMe(toDelete: List<ChatMessage>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                toDelete.forEach { repo.deleteForMe(context, id, it.id) }
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    /** Smaže vybrané MOJE zprávy pro všechny (náhrobek u mě i u protějšku). */
    fun deleteForEveryone(toDelete: List<ChatMessage>) {
        if (contact == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                toDelete.forEach { m ->
                    m.wireRef?.let { RelaySync.sendDeleteForEveryone(context, contact, it) }
                }
            }
            messages = withContext(Dispatchers.IO) { repo.getMessages(id) }
        }
    }

    /** Přepne vstupní pole do režimu úpravy dané (mojí) zprávy. */
    fun startEditing(message: ChatMessage) {
        editing = message
        input = message.text
        // Úprava a odpověď se vylučují; a vstupní pole je při hledání skryté.
        replyTo = null
        selectedIds = emptySet()
        searchMode = false; searchQuery = ""
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
        // Upravovaná zpráva mezitím zmizela nebo ji někdo smazal (i pro všechny) -
        // režim úpravy zruš a rozepsaný text zahoď, ať se neuloží do neexistujícího.
        editing?.let { e ->
            if (messages.none { it.id == e.id && !it.deleted }) {
                editing = null
                input = ""
            }
        }
    }

    // Systémové zpět nejdřív zavře výběr / hledání / rozepsanou odpověď, teprve
    // pak opustí konverzaci - jinak by uživatel omylem vyskočil z chatu.
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    BackHandler(enabled = selectedIds.isEmpty() && searchMode) {
        searchMode = false; searchQuery = ""
    }
    BackHandler(enabled = selectedIds.isEmpty() && !searchMode && replyTo != null) { replyTo = null }
    BackHandler(enabled = selectedIds.isEmpty() && !searchMode && editing != null) {
        editing = null; input = ""
    }

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
                            IconButton(onClick = {
                                replyTo = single; selectedIds = emptySet()
                                // Odpověď potřebuje vstupní řádek, který je při hledání
                                // skrytý - tak hledání zavři, ať se náhled i pole ukážou.
                                searchMode = false; searchQuery = ""
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = stringResource(R.string.chat_action_reply)
                                )
                            }
                        }
                        // Úprava jen u JEDNÉ mojí textové (nesmazané) zprávy.
                        if (single != null && canChat && ChatScreenLogic.canEdit(single)) {
                            IconButton(onClick = { startEditing(single) }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.chat_action_edit)
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
            } else if (searchMode) {
                // Hledání: lišta se přepne na vyhledávací pole. Šipka vlevo (jako
                // ve výchozí liště) hledání zruší; klávesnice se otevře sama.
                SearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = { searchMode = false; searchQuery = "" }
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
                        // Malý indikátor ztlumení vedle jména.
                        if (muted) {
                            Icon(
                                Icons.Default.NotificationsOff,
                                contentDescription = stringResource(R.string.muted_content_desc),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                        // Hledání v konverzaci - nahoře. Otevře vyhledávací lištu
                        // (a s ní rovnou klávesnici).
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_search)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            onClick = { menuOpen = false; searchQuery = ""; searchMode = true }
                        )
                        // Ztlumení oznámení - uprostřed. Když je ztlumeno, položka
                        // se přepne na „Zrušit ztlumení" a klepnutí rovnou odztlumí.
                        DropdownMenuItem(
                            text = { Text(stringResource(if (muted) R.string.menu_unmute else R.string.menu_mute)) },
                            leadingIcon = {
                                Icon(
                                    if (muted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuOpen = false
                                if (muted) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { MuteStore.unmute(context, id) }
                                        mutedUntil = null
                                    }
                                } else {
                                    showMuteDialog = true
                                }
                            }
                        )
                        // Profil kontaktu (detail + klíč) - dole, s ikonou ozubeného kola.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_menu_view_contact)) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; navController.navigate("user_detail/$id") }
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
                // Stejná generace, ale liší se schopnosti (nová funkce přes
                // capability bit) - neutrální „verze se liší".
                WireCompat.Peer.CAPS_DIFFER ->
                    ChatNotice(stringResource(R.string.chat_peer_caps_differ))
                WireCompat.Peer.OK -> {}
            }
            // Trust pinning: ověřený otisk klíče se změnil (podvržený / obnovený
            // klíč) → varuj a odkaž na nové ověření. Nezávislé na kompatibilitě verzí.
            if (trustChanged) {
                ChatNotice(stringResource(R.string.chat_trust_changed))
            }

            if (visibleMessages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        // V hledání „nic nenalezeno", jinak „zatím prázdno".
                        stringResource(if (searchMode) R.string.chat_search_no_results else R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.key }, contentType = { it::class }) { row ->
                        when (row) {
                            is ChatScreenLogic.ChatRow.DayHeader -> DayHeaderRow(row.epochDay)
                            is ChatScreenLogic.ChatRow.UnreadDivider -> UnreadDividerRow()
                            is ChatScreenLogic.ChatRow.Msg -> {
                                val m = row.message
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
                                    // Při hledání podbarvit nalezenou část; jinak prázdné.
                                    highlightQuery = if (searchMode) searchQuery else "",
                                    selected = m.id in selectedIds,
                                    // Pruh emoji jen když je vybraná JEN tahle jedna zpráva.
                                    showReactionPicker = selectedIds == setOf(m.id),
                                    canReact = canChat && m.wireRef != null && !m.deleted,
                                    selectionMode = selectedIds.isNotEmpty(),
                                    onSelect = { selectedIds = selectedIds + m.id },
                                    onTapInSelection = { selectedIds = ChatScreenLogic.toggleSelection(selectedIds, m.id) },
                                    onReact = { emoji -> react(m, emoji) },
                                    onMore = { emojiPickerFor = m; selectedIds = emptySet() },
                                    onDoubleTapReact = { doubleTapReact(m) },
                                    onReplySwipe = { if (canChat && m.wireRef != null && !m.deleted) replyTo = m },
                                    onRetry = { retry(m) },
                                    onQuoteClick = { quote.message?.let { jumpToMessage(it) } },
                                    highlighted = m.id == highlightedId
                                )
                            }
                        }
                    }
                }
                // Tlačítko „skočit dolů" - jen když nejsme u dna; odznak = počet nových.
                if (!atBottom) {
                    JumpToBottomButton(
                        count = newSinceScroll,
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(rows.lastIndex, SCROLL_BOTTOM_OFFSET)
                                stickToBottom = true
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    )
                }
                }
            }

            // Při hledání se dolní část (náhled odpovědi + psaní) skryje - lišta
            // teď hledá, ne píše, a seznam tak sedí celý nad klávesnicí.
            if (!searchMode) {
            // Náhled právě upravované zprávy (nad vstupním polem, jako odpověď).
            editing?.let { target ->
                EditComposerPreview(
                    message = target,
                    onCancel = { editing = null; input = "" }
                )
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
                        modifier = Modifier
                            .weight(1f)
                    )
                    FilledIconButton(
                        onClick = { sendCurrent() },
                        enabled = canChat && input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.content_desc_send))
                    }
                }
            }
            } // konec if (!searchMode) - skrytí spodní části při hledání
        }
    }

    // Plný emoji picker pro reakci (otevřený z „+" v paletě).
    emojiPickerFor?.let { target ->
        EmojiPickerSheet(
            onPick = { emoji -> applyReaction(target, emoji); emojiPickerFor = null },
            onDismiss = { emojiPickerFor = null }
        )
    }

    // Výběr délky ztlumení.
    if (showMuteDialog) {
        MuteDurationDialog(
            onPick = { until ->
                showMuteDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) { MuteStore.mute(context, id, until) }
                    mutedUntil = until
                }
            },
            onDismiss = { showMuteDialog = false }
        )
    }

    // Mazání se potvrzuje: je nevratné a u přijaté zprávy ji relay už smazal,
    // takže se nedá získat zpátky. U MOJICH zpráv nabídne i „smazat pro všechny".
    if (pendingDelete.isNotEmpty()) {
        val toDelete = pendingDelete
        val everyone = ChatScreenLogic.canDeleteForEveryone(toDelete)
        val deletedLabel = stringResource(R.string.chat_deleted)
        val deletedEveryoneLabel = stringResource(R.string.chat_deleted_everyone)
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (everyone) R.string.chat_delete_choose_body else R.string.chat_delete_body
                    )
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (everyone) {
                        TextButton(onClick = {
                            pendingDelete = emptyList()
                            deleteForEveryone(toDelete)
                            Toast.makeText(context, deletedEveryoneLabel, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.chat_delete_for_everyone)) }
                    }
                    TextButton(onClick = {
                        pendingDelete = emptyList()
                        deleteForMe(toDelete)
                        Toast.makeText(context, deletedLabel, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.chat_action_delete)) }
                }
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
 * Offset pro „odroluj na úplné dno". Velký kladný scrollOffset u poslední
 * položky posune seznam na jeho konec (LazyColumn ho ořízne), takže je vidět
 * i spodek vysoké poslední zprávy. Bez něj `scrollToItem(last)` skončí s vrškem
 * poslední zprávy nahoře = „skoro na konci".
 */
private const val SCROLL_BOTTOM_OFFSET = 1_000_000

/** Hlavička dne v proudu zpráv („Dnes" / „Včera" / datum). Vystředěný štítek. */
@Composable
private fun DayHeaderRow(epochDay: Long) {
    val today = remember { java.time.LocalDate.now().toEpochDay() }
    val label = when (ChatScreenLogic.dayLabel(epochDay, today)) {
        ChatScreenLogic.DayLabel.TODAY -> stringResource(R.string.chat_day_today)
        ChatScreenLogic.DayLabel.YESTERDAY -> stringResource(R.string.chat_day_yesterday)
        ChatScreenLogic.DayLabel.OLDER -> remember(epochDay) {
            java.time.LocalDate.ofEpochDay(epochDay).format(
                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/** Čára „Nové zprávy" nad první nepřečtenou zprávou (barva značky). */
@Composable
private fun UnreadDividerRow() {
    val color = MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.5f))
        Text(
            text = stringResource(R.string.chat_unread_divider),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.5f))
    }
}

/** Malé plovoucí tlačítko „skočit na konec" s odznakem počtu nových zpráv. */
@Composable
private fun JumpToBottomButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_jump_to_bottom)
            )
        }
        if (count > 0) {
            Badge(modifier = Modifier.align(Alignment.TopEnd)) { Text(count.toString()) }
        }
    }
}

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
    highlightQuery: String,
    selected: Boolean,
    showReactionPicker: Boolean,
    canReact: Boolean,
    selectionMode: Boolean,
    onSelect: () -> Unit,
    onTapInSelection: () -> Unit,
    onReact: (String) -> Unit,
    onMore: () -> Unit,
    onDoubleTapReact: () -> Unit,
    onReplySwipe: () -> Unit,
    onRetry: () -> Unit,
    onQuoteClick: () -> Unit,
    highlighted: Boolean
) {
    val haptics = LocalHapticFeedback.current
    // Krátké zvýraznění po skoku z citace (fade dovnitř i ven). Když highlighted
    // spadne (po prodlevě v ChatScreen), barva se plynule vytratí.
    val highlightBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else androidx.compose.ui.graphics.Color.Transparent,
        label = "quoteHighlight"
    )
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
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                else highlightBg
            )
            .padding(vertical = 2.dp)
    ) {
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
                highlightQuery = highlightQuery,
                onRetry = onRetry,
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect()
                },
                // Ve výběrovém režimu klepnutí přidává/odebírá zprávu z výběru.
                onTap = if (selectionMode) onTapInSelection else null,
                // Dvojklep = rychlá reakce; jen mimo výběr a když jde reagovat.
                onDoubleTap = if (!selectionMode && canReact) onDoubleTapReact else null,
                onQuoteClick = onQuoteClick
            )
            // Pruh emoji jako plovoucí Popup NAD bublinou - nerezervuje místo a
            // klidně překryje zprávu nad ní (jako WhatsApp). Nefokusovatelný, ať
            // dál jdou gesta na zbytek konverzace.
            //
            // Animace: při vyskočení scale 0.85→1 + fade in, při skrytí fade out.
            // Popup držíme namontovaný, dokud fade ven nedojede, aby se skrytí
            // opravdu ukázalo, ne jen zmizelo.
            //
            // POZOR na `tween`: `graphicsLayer { alpha = 0f }` NEVYPÍNÁ hit-testing,
            // takže složený Popup polyká doteky i když je neviditelný - a leží NAD
            // bublinou, tedy přes zprávu o řádek výš. S výchozí pružinou se alpha
            // k nule blíží asymptoticky, takže mrtvá zóna neměla konec a na
            // pomalejším telefonu se projevila jako „nejde klikat". Tween skončí
            // přesně na nule za PICKER_FADE_MS a `reactionPickerMounted` Popup hned
            // odmontuje. Podmínka je v ChatScreenLogic, aby šla otestovat.
            val pickerWanted = showReactionPicker && canReact
            val pickerAlpha by animateFloatAsState(
                targetValue = if (pickerWanted) 1f else 0f,
                animationSpec = tween(ChatScreenLogic.PICKER_FADE_MS),
                label = "pickerAlpha"
            )
            val pickerScale by animateFloatAsState(
                targetValue = if (pickerWanted) 1f else 0.85f,
                animationSpec = tween(ChatScreenLogic.PICKER_FADE_MS),
                label = "pickerScale"
            )
            if (ChatScreenLogic.reactionPickerMounted(pickerWanted, pickerAlpha)) {
                Popup(
                    popupPositionProvider = remember(message.outgoing) {
                        AboveAnchorPosition(alignEnd = message.outgoing)
                    },
                    properties = PopupProperties(focusable = false)
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            alpha = pickerAlpha
                            scaleX = pickerScale
                            scaleY = pickerScale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                    ) {
                        // Během mizení paleta nesmí reagovat: uživatel už mířil
                        // na zprávu pod ní, ne na emoji.
                        ReactionPicker(
                            mine = message.reactionOf(ChatMessage.REACTOR_ME),
                            onPick = { if (pickerWanted) onReact(it) },
                            onMore = { if (pickerWanted) onMore() }
                        )
                    }
                }
            }
        }
    }
}

/** Umístí Popup těsně NAD kotvu (bublinu), zarovnaný k její straně. */
private class AboveAnchorPosition(private val alignEnd: Boolean) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        val rawX = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(rawX.coerceIn(0, maxX), y)
    }
}

/**
 * Vodorovný pruh rychlých reakcí. Vybraná je zvýrazněná; na konci „+" otevře
 * plný emoji picker ([onMore]).
 */
@Composable
private fun ReactionPicker(mine: String?, onPick: (String) -> Unit, onMore: () -> Unit) {
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
            // „+" na konec pruhu - otevře plný emoji picker.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onMore() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.reaction_more),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    textColor: Color,
    onClick: (() -> Unit)? = null
) {
    if (quoted == null && !missing) return
    // Klik skočí na originál - jen když cíl v historii pořád je (u „nedostupné"
    // není kam skočit).
    val clickable = onClick != null && quoted != null
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (clickable) Modifier.clickable { onClick!!() } else Modifier)
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

/** Pruh nad vstupním polem v režimu úpravy: ikona tužky, „Upravit zprávu" a náhled textu. */
@Composable
private fun EditComposerPreview(
    message: ChatMessage,
    onCancel: () -> Unit
) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.chat_editing_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.content_desc_cancel_edit)
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

/**
 * Horní lišta v režimu hledání: šipka zpět (zruší hledání, jako ve výchozí
 * liště), uprostřed textové pole s šedým placeholderem „Hledat". Klávesnice se
 * otevře sama, jakmile se lišta objeví ([FocusRequester]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    // Klik na „Hledat" v menu rovnou otevře klávesnici na tomhle poli.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.content_desc_back)
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_search_hint)) },
                // Splyne s lištou - žádné vlastní pozadí ani podtržení.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
    )
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    quoted: ChatMessage? = null,
    quotedMissing: Boolean = false,
    peerName: String = "",
    highlightQuery: String = "",
    onRetry: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onQuoteClick: (() -> Unit)? = null
) {
    val outgoing = message.outgoing
    val deleted = message.deleted
    // Smazaná zpráva je vždy neutrální šedá (i moje odchozí), ať „Deleted" čte
    // jako šedý text - ne bílý na tyrkysové bublině.
    val bubbleColor = when {
        deleted -> MaterialTheme.colorScheme.surfaceVariant
        outgoing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        deleted -> MaterialTheme.colorScheme.onSurfaceVariant
        outgoing -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
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
                        onDoubleClick = onDoubleTap,
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
            if (deleted) {
                // Náhrobek: místo obsahu jen šedý kurzívní „Deleted" a čas.
                Text(
                    stringResource(R.string.chat_message_deleted),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = textColor.copy(alpha = 0.85f)
                )
                Text(
                    TIME_FORMAT.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                )
            } else {
                QuotedBlock(
                    quoted = quoted,
                    missing = quotedMissing,
                    peerName = peerName,
                    accent = accent,
                    textColor = textColor,
                    onClick = onQuoteClick
                )
                when (message.kind) {
                    ChatMessage.Kind.IMAGE -> ChatImage(path = message.mediaPath)
                    ChatMessage.Kind.FILE -> FileBubble(message = message, textColor = textColor, highlightQuery = highlightQuery)
                    else -> HighlightedText(
                        text = message.text,
                        query = highlightQuery,
                        color = textColor
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // „upraveno" u upravené zprávy, vedle času.
                    if (message.editedAt != null) {
                        Text(
                            stringResource(R.string.chat_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
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
    }
    // Reakce se u náhrobku vyprázdní, takže tady stejně nic nebude - ale ať je
    // to explicitní, u smazané zprávy pruh reakcí nekreslíme.
    if (!deleted) ReactionChips(reactions = message.visibleReactions, outgoing = outgoing)
    }
}

/**
 * Text bubliny s bílým podbarvením nalezené části při hledání. Když je [query]
 * prázdný nebo se nic nenajde, kreslí se prostý text. Rozsahy VŠECH výskytů
 * počítá čistá [ChatScreenLogic.highlightRanges] (otestovaná zvlášť) - takže
 * jedna zpráva se ve výsledcích neopakuje, ale všechny výskyty v ní se podbarví.
 *
 * Podbarvení je bílé s tmavým písmem, aby bylo čitelné na JAKÉKOLI bublině
 * (odchozí tyrkysová i příchozí šedá) i v tmavém motivu.
 */
@Composable
private fun HighlightedText(
    text: String,
    query: String,
    color: Color,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val ranges = if (query.isNotBlank()) ChatScreenLogic.highlightRanges(text, query) else emptyList()
    if (ranges.isEmpty()) {
        Text(text, color = color, style = style, maxLines = maxLines, overflow = overflow)
        return
    }
    val annotated = buildAnnotatedString {
        append(text)
        val hl = SpanStyle(background = Color.White, color = Color.Black.copy(alpha = 0.87f))
        ranges.forEach { r -> addStyle(hl, r.first, r.last + 1) }
    }
    Text(annotated, color = color, style = style, maxLines = maxLines, overflow = overflow)
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
private fun FileBubble(message: ChatMessage, textColor: Color, highlightQuery: String = "") {
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
            // Při hledání podbarvit i nalezenou část v NÁZVU souboru (filtr
            // matchuje i názvy) - ať sedí „co se najde" s „co se zvýrazní".
            HighlightedText(
                text = message.text,
                query = highlightQuery,
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
        // Jedna fajfka = doručeno na relay server.
        ChatMessage.Status.SENT -> Text(
            "✓",
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.7f)
        )
        // Dvě fajfky = protějšek si zprávu vyzvedl na zařízení a potvrdil to.
        ChatMessage.Status.DELIVERED -> Text(
            "✓✓",
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.7f)
        )
        ChatMessage.Status.FAILED -> Icon(
            Icons.Default.ErrorOutline, contentDescription = stringResource(R.string.content_desc_not_delivered),
            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error
        )
        // Přijaté zprávy (i rozpracovaný příjem souboru) stavovou ikonu nemají -
        // ta je jen u odchozích.
        ChatMessage.Status.RECEIVED, ChatMessage.Status.RECEIVING -> {}
    }
}
