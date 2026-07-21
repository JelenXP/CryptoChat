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

    /**
     * Seřadí kontakty podle poslední aktivity: kdo psal (nebo komu přišlo)
     * NEJNOVĚJI, je nahoře. Kontakty bez jediné zprávy spadnou dolů a mezi sebou
     * si drží dosavadní pořadí (pořadí přidání ze storage).
     *
     * Klíč se bere z [lastActivity] (id kontaktu → čas poslední zprávy v ms).
     * Chybějící záznam = žádná zpráva → `Long.MIN_VALUE`, tedy úplně dolů; díky
     * tomu je řazení jednoznačné (ne závislé na tom, jak `sortedByDescending`
     * zachází s `null`). Řazení je stabilní, takže shodné klíče (i ta spousta
     * `MIN_VALUE`) zachovají vstupní pořadí.
     */
    fun sortByActivity(contacts: List<Contact>, lastActivity: Map<String, Long>): List<Contact> =
        contacts.sortedByDescending { lastActivity[it.id] ?: Long.MIN_VALUE }

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
