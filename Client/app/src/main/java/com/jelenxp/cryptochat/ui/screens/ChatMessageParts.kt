package com.jelenxp.cryptochat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jelenxp.cryptochat.R

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
