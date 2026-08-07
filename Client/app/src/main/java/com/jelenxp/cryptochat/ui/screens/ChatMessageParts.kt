package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R
import com.jelenxp.cryptochat.ui.theme.LocalDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sdílené listové composable proudu zpráv — STEJNÉ v 1:1 [ChatScreen] i skupinovém
 * [GroupChatScreen]. Berou jen primitiva (žádnou závislost na `ChatMessage`/`GroupChatMessage`),
 * takže je oba adaptéry volají se svými daty. Cíl: vzhled těchhle prvků se edituje na JEDNOM
 * místě, ať 1:1 a skupina nikdy nedivergují (v tomhle vznikaly odchylkové bugy).
 */

/** Oddělovač dne („Dnes" / „Včera" / datum) — vystředěná pilulka. */
@Composable
internal fun ChatDayDivider(epochDay: Long) {
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
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Citace uvnitř bubliny — barevný pruh vlevo, autor a náhled textu; klik skočí na originál.
 * Volající předá už rozřešený [author] a [summary] (u fotky/souboru zkrácený popis); [missing]
 * = odpověď na zprávu, která už není → ukáže „není dostupná" místo obsahu.
 */
@Composable
internal fun ChatQuotedBlock(
    author: String,
    summary: String,
    missing: Boolean,
    accent: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null,
) {
    val clickable = onClick != null && !missing
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (clickable) Modifier.clickable { onClick!!() } else Modifier)
            .background(textColor.copy(alpha = 0.10f))
            .height(IntrinsicSize.Min)
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (!missing) {
                Text(author, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1)
                Text(
                    summary,
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

/** Čipy reakcí pod bublinou — jedna pilulka se všemi emoji × počet, zarovnaná na stranu bubliny. */
@Composable
internal fun ChatReactionChips(emojis: List<String>, outgoing: Boolean) {
    if (emojis.isEmpty()) return
    val grouped = emojis.groupingBy { it }.eachCount()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                grouped.forEach { (emoji, count) ->
                    Text(if (count > 1) "$emoji $count" else emoji, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Normalizovaný stav ODCHOZÍ zprávy pro fajfku. Obě appky si svůj enum sem mapují
 * ([NONE] = přijaté / bez stavu, nekreslí nic). */
internal enum class BubbleStatus { SENDING, SENT, DELIVERED, FAILED, NONE }

/**
 * Stavová fajfka odchozí zprávy — STEJNÁ v 1:1 i skupině: hodiny při odesílání,
 * „✓" doručeno na relay, „✓✓" vyzvednuto protějškem, chyba u selhání.
 */
@Composable
internal fun ChatStatusGlyph(status: BubbleStatus, tint: Color) {
    when (status) {
        BubbleStatus.SENDING -> Icon(
            Icons.Default.Schedule,
            contentDescription = stringResource(R.string.content_desc_sending),
            modifier = Modifier.size(14.dp),
            tint = tint.copy(alpha = 0.7f)
        )
        // Jedna fajfka = doručeno na relay server.
        BubbleStatus.SENT -> Text("✓", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f))
        // Dvě fajfky = protějšek si zprávu vyzvedl na zařízení a potvrdil to.
        BubbleStatus.DELIVERED -> Text("✓✓", style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f))
        BubbleStatus.FAILED -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = stringResource(R.string.content_desc_not_delivered),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error
        )
        BubbleStatus.NONE -> {}
    }
}

private val BUBBLE_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Vlastní bublina zprávy (klip, barva, dlouhý stisk / klepnutí, obsah, patička) —
 * STEJNÁ v 1:1 [ChatScreen] i skupinovém [GroupChatScreen]. Volající ji vloží do
 * zarovnávacího `Row` (podle [outgoing]) a případně obalí swipe gestem, výběrovým
 * pozadím, jménem odesílatele a paletou reakcí — tyhle vrstvy zůstávají u volajícího.
 *
 * Obsah fotky/souboru dodá volající přes [media] slot se svým dekodérem; když je
 * null, kreslí se [text]. Citaci dodá [quoted] slot (dostane už odvozené barvy).
 * Náhrobek smazané, patička (čas + „upraveno" + fajfka) a nápověda u selhání jsou
 * tady, aby se editovaly na JEDNOM místě a 1:1 se skupinou nedivergovaly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubbleContent(
    outgoing: Boolean,
    deleted: Boolean,
    status: BubbleStatus,
    text: String,
    timestampMillis: Long,
    editedAt: Long?,
    highlightQuery: String,
    modifier: Modifier = Modifier,
    quoted: (@Composable (accent: Color, textColor: Color) -> Unit)? = null,
    media: (@Composable (textColor: Color) -> Unit)? = null,
    tapOpensMedia: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onTapInSelection: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onImageClick: (() -> Unit)? = null,
    onRetry: () -> Unit = {},
) {
    val outAccent = LocalDesign.current.accent
    // Smazaná zpráva je vždy neutrální šedá (i moje odchozí), ať „Deleted" čte jako
    // šedý text - ne bílý na tyrkysové bublině. Vlastní bublina má odstín podle accentu.
    val bubbleColor = when {
        deleted -> MaterialTheme.colorScheme.surfaceVariant
        outgoing -> outAccent.bubble
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        deleted -> MaterialTheme.colorScheme.onSurfaceVariant
        outgoing -> outAccent.onBubble
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Barva pruhu/autora citace: uvnitř mojí bubliny `onBubble`, u cizí `primary`.
    val accent = if (outgoing) outAccent.onBubble else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp
    )
    val failed = status == BubbleStatus.FAILED

    Column(
        modifier = modifier
            .widthIn(max = 300.dp)
            .clip(shape)
            .background(bubbleColor)
            // Dlouhý stisk otevírá výběr. Klepnutí: ve výběru přepíná označení
            // ([onTapInSelection]), jinak opakuje NEúspěšnou odchozí zprávu, případně
            // otevře fotku fullscreen. Pořadí je záměrné - retry MUSÍ být před fotkou,
            // aby u FAILED fotky klepnutí opakovalo odeslání, ne otevíralo náhled.
            .then(
                if (onLongPress != null) Modifier.combinedClickable(
                    onLongClick = onLongPress,
                    onDoubleClick = onDoubleTap,
                    onClick = {
                        when {
                            onTapInSelection != null -> onTapInSelection()
                            failed && outgoing -> onRetry()
                            tapOpensMedia && !deleted && onImageClick != null -> onImageClick()
                        }
                    }
                ) else if (failed && outgoing) Modifier.clickable { onRetry() } else Modifier
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
                BUBBLE_TIME_FORMAT.format(Date(timestampMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
            )
        } else {
            if (quoted != null) quoted(accent, textColor)
            if (media != null) media(textColor)
            else HighlightedText(text = text, query = highlightQuery, color = textColor, format = true)
            Row(
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // „upraveno" u upravené zprávy, vedle času.
                if (editedAt != null) {
                    Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                }
                Text(BUBBLE_TIME_FORMAT.format(Date(timestampMillis)), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                if (outgoing) ChatStatusGlyph(status, textColor)
            }
            if (failed) {
                // Odchozí = „klepni pro opakování"; příchozí (např. soubor) = „přijetí selhalo".
                Text(
                    stringResource(if (outgoing) R.string.chat_retry_hint else R.string.chat_receive_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
