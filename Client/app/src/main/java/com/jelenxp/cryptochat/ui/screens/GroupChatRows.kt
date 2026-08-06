package com.jelenxp.cryptochat.ui.screens

import com.jelenxp.cryptochat.chat.GroupChatMessage
import java.time.Instant
import java.time.ZoneId

/**
 * Řádky skupinové konverzace = zprávy + oddělovače DNŮ (jako 1:1 `ChatScreenLogic`).
 * Vytaženo z composable, ať jde otestovat (pravidlo 2). Den se počítá v LOKÁLNÍ
 * zóně (shodně s `ChatScreenLogic.dayLabel`, které porovnává proti `LocalDate.now`).
 */
object GroupChatRows {

    sealed interface Row {
        val key: String
        data class Day(val epochDay: Long) : Row {
            override val key get() = "day_$epochDay"
        }
        data class Msg(val message: GroupChatMessage) : Row {
            // Klíč musí být unikátní i při KOLIZI msgId: repo záměrně drží dvě zprávy se
            // stejným msgId od různých odesílatelů (anti-cenzura), takže `msgId` sám by
            // LazyColumn shodil na „Key already used". Identita řádku = (sender, msgId),
            // stejně jako dedup v GroupChatRepository. (Audit 2026-08-03-groups-2, kritická.)
            override val key get() = (message.senderMemberIdHex ?: "") + ":" + message.msgIdHex
        }
    }

    /** Epoch-day v lokální zóně (pro seskupení podle kalendářního dne). */
    fun epochDayOf(timestampMs: Long): Long =
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** Seřadí podle času a vloží [Row.Day] před první zprávu každého nového dne. */
    fun build(messages: List<GroupChatMessage>): List<Row> {
        val rows = ArrayList<Row>(messages.size + 4)
        var lastDay = Long.MIN_VALUE
        for (m in messages.sortedBy { it.timestamp }) {
            val day = epochDayOf(m.timestamp)
            if (day != lastDay) {
                rows.add(Row.Day(day))
                lastDay = day
            }
            rows.add(Row.Msg(m))
        }
        return rows
    }
}
