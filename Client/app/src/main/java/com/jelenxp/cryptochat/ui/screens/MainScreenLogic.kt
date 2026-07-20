package com.jelenxp.cryptochat.ui.screens

import com.jelenxp.cryptochat.chat.ChatMessage
import com.jelenxp.cryptochat.data.Contact

/**
 * Čistá logika seznamu kontaktů - **vytažená z `@Composable`, aby šla otestovat**
 * (viz testovací politika v `CLAUDE.md`).
 *
 * Podtitulek se schválně vrací jako [Subtitle], ne jako hotový řetězec: samotné
 * ROZHODNUTÍ (co ukázat) je tady a dá se otestovat bez Androidu, kdežto překlad
 * na text (přes `stringResource`) zůstává v composable. Bug se skrývá právě
 * v rozhodnutí - třeba v pořadí priorit (chybějící klíč musí vyhrát nad starou
 * zprávou), ne v tom, jaký string se zobrazí.
 */
object MainScreenLogic {

    /**
     * Filtr kontaktů podle hledaného textu. Prázdný dotaz = všichni; jinak
     * porovnání jména bez ohledu na velikost písmen a okolní mezery.
     */
    fun filterContacts(contacts: List<Contact>, query: String): List<Contact> {
        val q = query.trim()
        if (q.isEmpty()) return contacts
        return contacts.filter { it.name.contains(q, ignoreCase = true) }
    }

    /** Co ukázat jako podtitulek kontaktu v seznamu. */
    sealed interface Subtitle {
        /** Kontakt nemá klíč - nedá se s ním zatím psát. */
        data object NoKey : Subtitle

        /** Klíč je, ale žádná zpráva zatím nedorazila. */
        data object NoMessages : Subtitle

        /** Náhled poslední zprávy. [fromMe] = odchozí (prefix „Ty:"). */
        data class Last(
            val kind: ChatMessage.Kind,
            val text: String,
            val fromMe: Boolean
        ) : Subtitle
    }

    /**
     * Rozhodne, co bude v podtitulku kontaktu.
     *
     * **Priorita je záměrná:** chybějící klíč vyhraje i tehdy, když v historii
     * leží stará zpráva (třeba po obnově klíče) - jinak by seznam tvrdil, že jde
     * psát, i když ještě ne.
     */
    fun contactSubtitle(hasKey: Boolean, lastMessage: ChatMessage?): Subtitle = when {
        !hasKey -> Subtitle.NoKey
        lastMessage != null -> Subtitle.Last(
            kind = lastMessage.kind,
            text = lastMessage.text,
            fromMe = lastMessage.outgoing
        )
        else -> Subtitle.NoMessages
    }
}
