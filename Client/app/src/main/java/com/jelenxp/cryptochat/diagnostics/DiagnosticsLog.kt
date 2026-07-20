package com.jelenxp.cryptochat.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kruhový buffer posledních diagnostických záznamů (jen v paměti, nikdy na disk).
 *
 * Slouží k jedinému účelu: když uživatel dobrovolně pošle hlášení chyby, přiloží
 * se k němu stopa toho, co se v appce dělo - stav Toru, jestli šlo dojít na relay,
 * kolik blobů se vyzvedlo, proč se poll smyčka zpomalila. Bez toho jsou hlášení
 * typu „zprávy nechodí" nediagnostikovatelná.
 *
 * **Co se sem NIKDY nesmí zapsat** (a proto to volající musí hlídat na místě volání):
 *  - obsah zpráv, ani zašifrovaný, ani jeho útržek,
 *  - šifrovací klíče a cokoliv z nich odvozené,
 *  - jména kontaktů (jejich ID je náhodné UUID, to je v pořádku),
 *  - **ID schránek** (mailbox ID) - ani zkrácené: server by podle nich dokázal
 *    spojit hlášení s konkrétní konverzací,
 *  - adresu relaye (zvlášť `.onion`), cesty k médiím, hesla.
 *
 * Buffer je thread-safe (čte se z UI vlákna, zapisuje z IO smyček služby) a drží
 * jen [CAPACITY] posledních záznamů - starší tiše vypadnou. Zápis nikdy nevyhodí
 * výjimku, aby logování nemohlo shodit cestu, kterou popisuje.
 */
object DiagnosticsLog {

    /** Kolik záznamů se drží (starší se zahazují). */
    const val CAPACITY = 300

    /** Strop délky jedné zprávy - ať se do bufferu nevejde nic nečekaně velkého. */
    private const val MAX_MESSAGE_CHARS = 300

    enum class Level { INFO, WARN, ERROR }

    /** Jeden záznam: kdy, jak vážné, odkud a co. */
    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private val lock = Any()

    /** Kruhový buffer - přidává se na konec, při přeplnění se ubírá ze začátku. */
    private val entries = ArrayDeque<Entry>(CAPACITY)

    /** Onion adresy v cizích textech (typicky logy Toru) - nesmí se zapsat. */
    private val ONION_RE = Regex("[a-z2-7]{16,56}\\.onion", RegexOption.IGNORE_CASE)

    fun log(tag: String, message: String) = add(Level.INFO, tag, message)

    fun warn(tag: String, message: String) = add(Level.WARN, tag, message)

    fun error(tag: String, message: String) = add(Level.ERROR, tag, message)

    private fun add(level: Level, tag: String, message: String) {
        try {
            val entry = Entry(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message.take(MAX_MESSAGE_CHARS)
            )
            synchronized(lock) {
                if (entries.size >= CAPACITY) entries.removeFirst()
                entries.addLast(entry)
            }
        } catch (e: Exception) {
            // Logování nesmí nikdy shodit volajícího - radši záznam zahodit.
        }
    }

    /** Snímek bufferu (nejstarší první). Kopie, takže volající nedrží zámek. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** Vyprázdní buffer (např. po odeslání hlášení). */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * Vrátí log jako čitelný text, jeden záznam na řádek:
     * `MM-dd HH:mm:ss I/Tag: zpráva`. Prázdný buffer = prázdný řetězec.
     */
    fun dump(): String = dumpLines().joinToString("\n")

    /** Log po řádcích (hodí se pro JSON pole v hlášení chyby). */
    fun dumpLines(): List<String> {
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        return snapshot().map { entry ->
            val time = format.format(Date(entry.timestamp))
            "$time ${entry.level.name.first()}/${entry.tag}: ${entry.message}"
        }
    }

    /**
     * Odstraní z cizího textu `.onion` adresy. Používá se u řádků, které appka
     * sama nesložila (logy Toru) - ty by jinak mohly adresu relaye vypsat.
     */
    fun redact(text: String): String = ONION_RE.replace(text, "<onion>")
}
