package com.jelenxp.cryptochat.chat

/**
 * Jedna zpráva v lokální historii konverzace. Historie leží jen na zařízení
 * (šifrovaně, viz [ChatRepository]) - relay si žádné zprávy nedrží.
 */
data class ChatMessage(
    val id: String,
    val outgoing: Boolean,     // true = odeslaná mnou, false = přijatá
    val text: String,
    val timestamp: Long,       // epoch millis (čas odeslání)
    val status: Status
) {
    enum class Status { SENDING, SENT, FAILED, RECEIVED }
}
