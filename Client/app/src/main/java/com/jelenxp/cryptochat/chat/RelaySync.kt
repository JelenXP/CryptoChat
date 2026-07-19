package com.jelenxp.cryptochat.chat

import android.content.Context
import com.jelenxp.cryptochat.data.Contact
import com.jelenxp.cryptochat.data.SettingsRepository
import java.util.UUID

/**
 * Doručování zpráv přes relay. Šifrovací vrstva je hotová jinde - tady je jen
 * „doprava": odesílání do schránky odesílatele a vyzvedávání ze schránky
 * příjemce.
 *
 * **Směr a rotace schránek:** každý kontakt má dvě schránky (dir 0 a 1). Kdo
 * posílá na kterou, plyne z role při párování ([Contact.initiator]): iniciátor
 * posílá na dir 0 a poslouchá na dir 1, odpovídající naopak. ID schránky se
 * navíc mění podle epochy (aktuálně 1 den) - server tak nespojí konverzaci
 * napříč dny. Příjemce kontroluje aktuální i předchozí epochu (kvůli přelomu dne).
 *
 * Metody blokují (síť) - volej z IO dispatcheru.
 */
object RelaySync {

    // Délka jedné epochy schránky (rotace). 1 den = rozumný kompromis mezi
    // soukromím (časté střídání ID) a spolehlivostí (server drží blob 24 h).
    private const val EPOCH_MS = 24L * 60 * 60 * 1000

    private fun currentEpoch() = System.currentTimeMillis() / EPOCH_MS

    /** Směr, na který kontakt POSÍLÁ. Iniciátor = 0, odpovídající = 1. */
    private fun sendDir(contact: Contact) = if (contact.initiator == true) 0 else 1

    /** Směr, na kterém kontakt POSLOUCHÁ (opačný). */
    private fun recvDir(contact: Contact) = 1 - sendDir(contact)

    /**
     * Zapíše zprávu do lokální historie se stavem SENDING a vrátí ji. Nedělá síť -
     * díky tomu se dá hned zobrazit v UI. Doručení pak dokončí [deliver].
     */
    fun enqueue(context: Context, contact: Contact, text: String): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            outgoing = true,
            text = text,
            timestamp = System.currentTimeMillis(),
            status = ChatMessage.Status.SENDING
        )
        ChatRepository(context).append(contact.id, message)
        return message
    }

    /**
     * Zašifruje a odešle už zařazenou zprávu do schránky a aktualizuje její stav
     * (SENT/FAILED). Vrací, zda se doručila.
     */
    fun deliver(context: Context, contact: Contact, message: ChatMessage): Boolean {
        val key = contact.keyBase64
        val baseUrl = SettingsRepository(context).getRelayUrl()
        val delivered = try {
            if (key.isNullOrBlank() || baseUrl.isBlank()) {
                false
            } else {
                val blob = ChatEnvelope.seal(message.text, message.timestamp, key)
                val mailbox = RelayCrypto.mailboxId(key, sendDir(contact), currentEpoch())
                RelayClient.put(baseUrl, mailbox, blob)
            }
        } catch (e: Exception) {
            false
        }
        val finalStatus = if (delivered) ChatMessage.Status.SENT else ChatMessage.Status.FAILED
        ChatRepository(context).updateStatus(contact.id, message.id, finalStatus)
        return delivered
    }

    /**
     * Vyzvedne nové zprávy pro daný kontakt a uloží je do historie. Vrací počet
     * nově přijatých zpráv. Síťové chyby spolkne (vrátí 0) - poll běží opakovaně.
     */
    fun poll(context: Context, contact: Contact): Int {
        val key = contact.keyBase64 ?: return 0
        val baseUrl = SettingsRepository(context).getRelayUrl()
        if (baseUrl.isBlank()) return 0

        val repo = ChatRepository(context)
        val dir = recvDir(contact)
        val epoch = currentEpoch()
        var received = 0
        // Aktuální i předchozí epocha (přelom dne / rozjeté hodiny).
        for (e in longArrayOf(epoch, epoch - 1)) {
            val mailbox = RelayCrypto.mailboxId(key, dir, e)
            val blobs = try {
                RelayClient.get(baseUrl, mailbox)
            } catch (ex: Exception) {
                continue
            }
            for (blob in blobs) {
                val opened = ChatEnvelope.open(blob, key) ?: continue
                repo.append(
                    contact.id,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        outgoing = false,
                        text = opened.text,
                        timestamp = opened.timestamp,
                        status = ChatMessage.Status.RECEIVED
                    )
                )
                received++
            }
        }
        return received
    }
}
