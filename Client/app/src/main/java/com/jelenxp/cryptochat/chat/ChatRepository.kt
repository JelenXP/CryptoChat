package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.crypto.KeystoreStorageCrypto
import com.jelenxp.cryptochat.crypto.StorageCrypto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lokální historie zpráv jednotlivých konverzací. Ukládá se jako JSON do
 * SharedPreferences, celé pole je před uložením zašifrované klíčem z Android
 * Keystore (viz [StorageCrypto]) - na disku tedy nikdy neleží čitelné.
 *
 * Stejně jako [com.jelenxp.cryptochat.data.ContactRepository] je odolné proti
 * výjimkám: poškozená data vrátí prázdný seznam, zápis vrací Boolean, appka
 * kvůli němu nikdy nespadne.
 *
 * **Souběh:** historii mění zároveň poll smyčka služby na pozadí i UI. Každá
 * úprava je read-modify-write nad celým polem, takže bez zámku by si zápisy
 * navzájem přepisovaly - a protože relay zprávu po vyzvednutí MAŽE, ztracená
 * příchozí zpráva by byla ztracená nenávratně. Všechny operace proto běží pod
 * jedním procesovým zámkem [lock].
 *
 * **Cache:** dešifrování Keystorem + parsování JSONu je drahé a náhledy v
 * seznamu kontaktů se čtou často. Rozparsovaná historie se proto drží v paměti
 * ([cache]); zápisy ji rovnou aktualizují. Instance téhle třídy jsou levné a
 * zahoditelné - stav je společný přes companion object.
 */
class ChatRepository(
    context: Context,
    /**
     * Šifrování at rest. Výchozí je Keystore; testy si dosadí průhlednou
     * implementaci, jinak by tenhle repozitář nešlo otestovat vůbec.
     */
    private val crypto: StorageCrypto = KeystoreStorageCrypto
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Zprávy konverzace seřazené od nejstarší po nejnovější. */
    fun getMessages(contactId: String): List<ChatMessage> = synchronized(lock) {
        loadLocked(contactId)
    }

    /**
     * Načte historii pro ČTENÍ (z cache, jinak z disku). Při chybě vrací prázdný
     * seznam - obrazovka pak ukáže prázdnou konverzaci místo pádu.
     *
     * **K zápisu NEPOUŽÍVAT**, na to je [loadForWriteLocked]. Volej jen pod [lock].
     */
    private fun loadLocked(contactId: String): List<ChatMessage> =
        loadForWriteLocked(contactId) ?: emptyList()

    /**
     * Načte historii pro ZÁPIS. Vrací `null`, když se ji nepodařilo přečíst -
     * na rozdíl od prázdné historie, což je legitimní stav.
     *
     * **Proč to rozlišení musí být:** zápis je read-modify-write nad celým polem.
     * Kdyby se při nepovedeném čtení tvářilo, že je historie prázdná, uložil by
     * se přes tu skutečnou seznam s jedinou zprávou - a celá konverzace by byla
     * pryč. Selhat je tady jediná bezpečná možnost: volající zápis nepotvrdí,
     * relay zprávu podrží a příště to vyjde.
     *
     * Volej jen pod [lock].
     */
    private fun loadForWriteLocked(contactId: String): List<ChatMessage>? {
        cache[contactId]?.let { return it }
        // Neúspěšné čtení (null) NEcachujeme - jinak by si appka zapamatovala
        // prázdnou historii a první zápis by tu skutečnou na disku přepsal.
        val loaded = readFromDisk(contactId) ?: return null
        cache[contactId] = loaded
        return loaded
    }

    /** Přečte historii z disku. Vrací null, když se to nepovedlo (na rozdíl od prázdné). */
    private fun readFromDisk(contactId: String): List<ChatMessage>? {
        return try {
            val stored = prefs.getString(key(contactId), null) ?: return emptyList()
            val json = crypto.decrypt(stored) ?: return null
            val array = JSONArray(json)
            val result = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val status = runCatching { ChatMessage.Status.valueOf(o.optString("st")) }
                    .getOrDefault(ChatMessage.Status.SENT)
                val kind = runCatching { ChatMessage.Kind.valueOf(o.optString("kind", "TEXT")) }
                    .getOrDefault(ChatMessage.Kind.TEXT)
                result.add(
                    ChatMessage(
                        id = o.optString("id"),
                        outgoing = o.optBoolean("out"),
                        text = o.optString("txt"),
                        timestamp = o.optLong("ts"),
                        status = status,
                        kind = kind,
                        mediaPath = if (o.has("media")) o.optString("media") else null,
                        mimeType = if (o.has("mime")) o.optString("mime") else null,
                        wireId = if (o.has("wid")) o.optString("wid") else null,
                        replyToWireId = if (o.has("rto")) o.optString("rto") else null,
                        editedAt = if (o.has("ed")) o.optLong("ed") else null,
                        deleted = o.optBoolean("del", false),
                        reactions = readReactions(o.optJSONObject("rx"))
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se načíst historii (${e.javaClass.simpleName})")
            null
        }
    }

    /** Přidá zprávu na konec konverzace. Vrací true při úspěchu. */
    fun append(contactId: String, message: ChatMessage): Boolean = synchronized(lock) {
        val current = loadForWriteLocked(contactId) ?: return false
        saveLocked(contactId, current + message)
    }

    /**
     * Přidá zprávu jen tehdy, když v konverzaci ještě není zpráva se stejným id.
     * Používá se u příjmu souborů, kde id = hex `fileId`: kdyby odesílatel poslal
     * stejný soubor znovu, vznikly by dvě zprávy se shodným id a `LazyColumn`
     * (klíčovaný právě id) by shodil obrazovku chatu. Vrací true, když se přidala.
     */
    fun appendIfAbsent(contactId: String, message: ChatMessage): AppendResult = synchronized(lock) {
        // Musí rozlišit „už tam byla" od „nepodařilo se přečíst historii".
        // Kdyby se obojí slévalo do `false`, volající by neúspěch považoval za
        // duplicitu, dávku by potvrdil a relay by zprávu smazal - nenávratně.
        val current = loadForWriteLocked(contactId) ?: return AppendResult.FAILED
        if (current.any { it.id == message.id }) return AppendResult.DUPLICATE
        if (saveLocked(contactId, current + message)) AppendResult.ADDED else AppendResult.FAILED
    }

    /** Jak dopadlo přidání zprávy, u které hlídáme duplicitu. */
    enum class AppendResult {
        /** Uložena. */
        ADDED,

        /** Už ji máme (stejné `wireId`) - není to chyba, jen se nic nepřidalo. */
        DUPLICATE,

        /** Zápis selhal. */
        FAILED
    }

    /**
     * Přidá příchozí zprávu, pokud v konverzaci ještě není zpráva se stejným
     * [ChatMessage.wireId].
     *
     * `wireId` volí odesílatel, takže tohle je druhá vrstva ochrany proti
     * duplicitám: [ReplayGuard] pozná jen shodný blob, ale tatáž zpráva poslaná
     * znovu má jiné IV, a tedy i jiný otisk. Bez téhle kontroly by se v historii
     * objevila dvakrát.
     *
     * Rozlišuje „už tam byla" od „nepovedlo se" schválně - volající podle toho
     * pozná, jestli smí zvýšit počítadlo nepřečtených.
     */
    fun appendIfAbsentByWireId(contactId: String, message: ChatMessage): AppendResult =
        synchronized(lock) {
            val wireId = message.wireId ?: return AppendResult.FAILED
            // Když se historii NEPODAŘILO přečíst, nesmí se zapisovat (viz
            // loadForWriteLocked) - dávka se nepotvrdí a zpráva dorazí znovu.
            val current = loadForWriteLocked(contactId) ?: return AppendResult.FAILED
            // Porovnává se JEN proti příchozím zprávám. Naše odchozí ID protějšek
            // zná (posíláme mu je v traileru), takže by stačilo, aby jedno z nich
            // poslal zpátky, a jeho zpráva by se tiše zahodila jako duplicita.
            if (current.any { it.wireId == wireId && !it.outgoing }) {
                return AppendResult.DUPLICATE
            }
            if (saveLocked(contactId, current + message)) AppendResult.ADDED
            else AppendResult.FAILED
        }

    /**
     * Nastaví stav existující zprávy (např. SENDING -> SENT/FAILED).
     *
     * **DELIVERED se nedegraduje.** Je to terminální stav odchozí zprávy (druhá
     * fajfka). Souběh je reálný: doručenku zpracuje poll smyčka služby
     * ([RelaySync] na jednom vlákně) a `updateStatus(SENT/SENDING/FAILED)` volá
     * `deliver`/`retry` z UI vlákna nad tímtéž kontaktem. Bez téhle ochrany by
     * pozdější `updateStatus` mohl DELIVERED tiše přepsat na SENT - a to už by se
     * nezhojilo, protože protějšek zprávu má a novou doručenku nepošle. Stejná
     * „upgrade-only" logika jako v [markDelivered].
     */
    fun updateStatus(contactId: String, messageId: String, status: ChatMessage.Status): Boolean =
        synchronized(lock) {
            val current = loadForWriteLocked(contactId) ?: return false
            val updated = current.map {
                when {
                    it.id != messageId -> it
                    it.status == ChatMessage.Status.DELIVERED && isOutgoingDowngrade(status) -> it
                    else -> it.copy(status = status)
                }
            }
            saveLocked(contactId, updated)
        }

    /** Je [status] „horší" odchozí stav než DELIVERED (nesmí ho přebít)? */
    private fun isOutgoingDowngrade(status: ChatMessage.Status): Boolean =
        status == ChatMessage.Status.SENDING ||
            status == ChatMessage.Status.SENT ||
            status == ChatMessage.Status.FAILED

    /**
     * Označí NAŠI odchozí zprávu za doručenou na zařízení protějšku (druhá
     * fajfka), podle jejího [ChatMessage.wireRef]. Volá se, když dorazí potvrzení
     * doručení ([ChatEnvelope.Opened.Delivery]).
     *
     * Přepíná jen odchozí zprávu a jen ze stavu SENDING/SENT/FAILED - DELIVERED
     * je stav „nejlepší z nich" (i FAILED se smí opravit na DELIVERED: doručení
     * mohlo projít, jen se nám nevrátilo 2xx). Je idempotentní: už doručenou
     * zprávu nepřepisuje (žádný zbytečný zápis).
     *
     * Vrací [DeliveryResult] - selhání zápisu MUSÍ jít odlišit, aby volající
     * potvrzení nepotvrdil a dorazilo znovu.
     */
    fun markDelivered(contactId: String, wireRef: String): DeliveryResult = synchronized(lock) {
        val current = loadForWriteLocked(contactId) ?: return DeliveryResult.FAILED
        val index = current.indexOfFirst { it.outgoing && it.wireRef == wireRef }
        // Cíl tu není (uživatel zprávu smazal, nebo potvrzení míří na neznámé ID).
        // Není to chyba - potvrzení se smí potvrdit, jen není co aktualizovat.
        if (index < 0) return DeliveryResult.TARGET_MISSING
        val message = current[index]
        // Idempotence: DELIVERED už je konečný stav, znovu nezapisuj.
        if (message.status == ChatMessage.Status.DELIVERED) return DeliveryResult.UPDATED
        // DELIVERED přebíjí jen „horší" odchozí stavy. RECEIVED/RECEIVING sem
        // nikdy nepatří (jsou to příchozí / soubor v příjmu), ale pro jistotu je
        // nepřepisujeme.
        val upgradable = message.status == ChatMessage.Status.SENDING ||
            message.status == ChatMessage.Status.SENT ||
            message.status == ChatMessage.Status.FAILED
        if (!upgradable) return DeliveryResult.TARGET_MISSING
        val updated = current.toMutableList().also {
            it[index] = message.copy(status = ChatMessage.Status.DELIVERED)
        }
        if (saveLocked(contactId, updated)) DeliveryResult.UPDATED else DeliveryResult.FAILED
    }

    /** Jak dopadlo označení zprávy za doručenou. */
    enum class DeliveryResult {
        /** Zpráva označena za doručenou (nebo už doručená byla). */
        UPDATED,

        /** Cílová odchozí zpráva v historii není - potvrzení se smí potvrdit. */
        TARGET_MISSING,

        /** Zápis selhal - potvrzení nepotvrzuj, ať dorazí znovu. */
        FAILED
    }

    /** Doplní cestu k souboru a stav (po složení všech kousků přijatého souboru). */
    fun updateMedia(
        contactId: String,
        messageId: String,
        mediaPath: String?,
        status: ChatMessage.Status
    ): Boolean = synchronized(lock) {
        val current = loadForWriteLocked(contactId) ?: return false
        val updated = current.map {
            if (it.id == messageId) it.copy(mediaPath = mediaPath, status = status) else it
        }
        saveLocked(contactId, updated)
    }

    /**
     * Smaže jednu zprávu **jen u nás**. Protějšku nic neposílá a jeho kopie
     * zůstává - proto „smazat u sebe".
     *
     * Uklidí i přiloženou fotku/soubor, ať po smazané zprávě nezůstane osiřelý
     * soubor. Přijímaný soubor (RECEIVING) se maže taky, ale jeho rozpracované
     * kousky si uklidí [MediaTransfers] samo.
     */
    fun deleteMessage(context: Context, contactId: String, messageId: String): Boolean =
        synchronized(lock) {
            val current = loadForWriteLocked(contactId) ?: return false
            val target = current.firstOrNull { it.id == messageId } ?: return false
            if (!saveLocked(contactId, current.filterNot { it.id == messageId })) return false
            // Až po úspěšném zápisu - kdyby se neuložil, zpráva zůstane a s ní
            // musí zůstat i její soubor.
            if (target.kind != ChatMessage.Kind.TEXT) {
                target.mediaPath?.let { path -> runCatching { java.io.File(path).delete() } }
            }
            // Rozpracovaný příjem souboru: `mediaPath` je ještě null, takže výše
            // není co mazat. Bez tohohle by se kousky doskládaly do souboru, na
            // který už nic neodkazuje, a zůstal by na disku napořád.
            if (target.kind == ChatMessage.Kind.FILE) {
                runCatching {
                    MediaTransfers.clearProgress(target.id)
                    MediaTransfers.cleanup(context, target.id)
                }
            }
            true
        }

    /**
     * Nastaví nebo zruší reakci u zprávy s daným [ChatMessage.wireRef].
     *
     * [timestamp] chrání před přeházeným pořadím: starší reakce nikdy nepřebije
     * novější. Bez toho by opožděná reakce z karantény mohla vrátit zpátky
     * emoji, které už uživatel zrušil.
     *
     * Vrací true, když se cílová zpráva našla a stav se uložil.
     */
    fun setReaction(
        contactId: String,
        wireRef: String,
        reactor: String,
        emoji: String?,
        timestamp: Long
    ): ReactionResult = synchronized(lock) {
        val current = loadForWriteLocked(contactId) ?: return ReactionResult.FAILED
        val index = current.indexOfFirst { it.wireRef == wireRef }
        // Cíl tu ještě není (zpráva nedorazila, nebo leží v karanténě). Volající
        // ji odloží a zkusí to znovu, až něco přijde.
        if (index < 0) return ReactionResult.TARGET_MISSING
        val message = current[index]
        // Náhrobek (smazaná zpráva) reakce NEpřijímá - smazání je terminální, ať
        // se emoji neobjeví na „Deleted" bublině (opožděná reakce z karantény).
        // APPLIED = zahoď a potvrď (cíl existuje, jen se na něj reagovat nedá) -
        // NE TARGET_MISSING, to by reakci znovu odložilo do fronty.
        if (message.deleted) return ReactionResult.APPLIED
        // Rozhodování o pořadí a náhrobcích je v ReactionMerge, ať jde otestovat.
        val updated = ReactionMerge.apply(message.reactions, reactor, emoji, timestamp)
            ?: return ReactionResult.APPLIED   // nic se nemění
        val saved = saveLocked(contactId, current.toMutableList().also {
            it[index] = message.copy(reactions = updated)
        })
        if (saved) ReactionResult.APPLIED else ReactionResult.FAILED
    }

    /** Jak dopadlo nastavení reakce. */
    enum class ReactionResult {
        /** Uloženo (nebo se nic měnit nemuselo). */
        APPLIED,

        /** Cílová zpráva zatím není v historii - zkusit později. */
        TARGET_MISSING,

        /** Zápis selhal. */
        FAILED
    }

    /** Jak dopadla úprava nebo smazání existující zprávy (edit/delete pro všechny). */
    enum class MutationResult {
        /** Uloženo (nebo se nic měnit nemuselo - už upraveno / už náhrobek). */
        APPLIED,

        /** Cílová zpráva zatím není v historii - zkusit později. */
        TARGET_MISSING,

        /** Zápis selhal. */
        FAILED
    }

    /**
     * Upraví text existující zprávy podle jejího [ChatMessage.wireRef].
     *
     * [outgoing] říká, KTEROU stranu smíš upravit: `true` = NAŠI odchozí (lokální
     * úprava vlastní zprávy), `false` = PŘÍCHOZÍ od protějšku (úprava dorazila po
     * drátě). Protějšek smí upravovat **jen svoje** zprávy - bez téhle podmínky by
     * stačilo, aby poslal úpravu s ID naší odchozí zprávy (to zná z traileru), a
     * přepsal by nám vlastní text (stejná past jako u dedupu podle `wireId`).
     *
     * Pořadí a náhrobky řeší [MessageMutationMerge], ať jde otestovat.
     */
    fun applyEdit(
        contactId: String,
        wireRef: String,
        newText: String,
        timestamp: Long,
        outgoing: Boolean
    ): MutationResult = synchronized(lock) {
        val current = loadForWriteLocked(contactId) ?: return MutationResult.FAILED
        val index = current.indexOfFirst { it.wireRef == wireRef && it.outgoing == outgoing }
        if (index < 0) return MutationResult.TARGET_MISSING
        val updated = MessageMutationMerge.applyEdit(current[index], newText, timestamp)
            ?: return MutationResult.APPLIED   // nic se nemění
        val saved = saveLocked(contactId, current.toMutableList().also { it[index] = updated })
        if (saved) MutationResult.APPLIED else MutationResult.FAILED
    }

    /**
     * Udělá z existující zprávy náhrobek („Deleted") podle [ChatMessage.wireRef]
     * a uklidí její soubor. Stejné pravidlo „kterou stranu smíš" jako [applyEdit]:
     * protějšek smí smazat jen svoje (příchozí) zprávy, my při „smazat pro všechny"
     * svoji odchozí ([outgoing] = `true`).
     */
    fun deleteForBoth(
        context: Context,
        contactId: String,
        wireRef: String,
        outgoing: Boolean
    ): MutationResult = synchronized(lock) {
        val current = loadForWriteLocked(contactId) ?: return MutationResult.FAILED
        val index = current.indexOfFirst { it.wireRef == wireRef && it.outgoing == outgoing }
        if (index < 0) return MutationResult.TARGET_MISSING
        val target = current[index]
        val updated = MessageMutationMerge.applyDelete(target)
            ?: return MutationResult.APPLIED   // už náhrobek
        if (!saveLocked(contactId, current.toMutableList().also { it[index] = updated }))
            return MutationResult.FAILED
        // Až po úspěšném zápisu - kdyby se neuložil, náhrobek nevznikl a soubor
        // musí zůstat.
        cleanupMedia(context, target)
        MutationResult.APPLIED
    }

    /**
     * Smaže zprávu **jen u mě** (podle lokálního [ChatMessage.id]): udělá z ní
     * náhrobek („Deleted") a uklidí její soubor. Na rozdíl od [deleteForBoth]
     * protějšku nic neposílá a hledá podle lokálního `id`, takže funguje i na
     * zprávy bez `wireRef` (staré, nebo příchozí od protějška).
     */
    fun deleteForMe(context: Context, contactId: String, messageId: String): Boolean =
        synchronized(lock) {
            val current = loadForWriteLocked(contactId) ?: return false
            val index = current.indexOfFirst { it.id == messageId }
            if (index < 0) return false
            val target = current[index]
            val updated = MessageMutationMerge.applyDelete(target) ?: return true // už náhrobek
            if (!saveLocked(contactId, current.toMutableList().also { it[index] = updated }))
                return false
            cleanupMedia(context, target)
            true
        }

    /**
     * Uklidí přiloženou fotku/soubor a rozpracovaný příjem po zprávě, ze které se
     * stal náhrobek (stejně jako [deleteMessage]). Přijímaný soubor (`mediaPath`
     * je ještě null) uklidí přes [MediaTransfers], ať po něm nezůstanou kousky.
     */
    private fun cleanupMedia(context: Context, target: ChatMessage) {
        if (target.kind != ChatMessage.Kind.TEXT) {
            target.mediaPath?.let { path -> runCatching { File(path).delete() } }
        }
        if (target.kind == ChatMessage.Kind.FILE) {
            runCatching {
                MediaTransfers.clearProgress(target.id)
                MediaTransfers.cleanup(context, target.id)
            }
        }
    }

    /** Zpráva podle [ChatMessage.wireRef] (pro náhled odpovědi). */
    fun findByWireRef(contactId: String, wireRef: String): ChatMessage? =
        synchronized(lock) { loadLocked(contactId).firstOrNull { it.wireRef == wireRef } }

    /** Poslední zpráva konverzace, nebo null když žádná není. */
    fun getLastMessage(contactId: String): ChatMessage? = getMessages(contactId).lastOrNull()

    /** Počet nepřečtených (příchozích) zpráv - hlídá se jen lokálně v appce. */
    fun getUnreadCount(contactId: String): Int = prefs.getInt(unreadKey(contactId), 0)

    /** Zvýší počítadlo nepřečtených o jednu (volá se při příjmu mimo otevřený chat). */
    fun incrementUnread(contactId: String) = synchronized(lock) {
        // Čtení a zápis musí být atomické, jinak se souběžné inkrementy překryjí
        // a odznak ukáže míň nepřečtených, než kolik jich doopravdy přišlo.
        prefs.edit().putInt(unreadKey(contactId), getUnreadCount(contactId) + 1).apply()
    }

    /** Označí konverzaci za přečtenou (volá se při otevření chatu). */
    fun markRead(contactId: String) = synchronized(lock) {
        // Pod zámkem, ať nepřepíše souběžný incrementUnread z poll smyčky.
        prefs.edit().putInt(unreadKey(contactId), 0).apply()
    }

    /** Nastaví počítadlo nepřečtených (použije se při obnově ze zálohy). */
    fun setUnread(contactId: String, count: Int) = synchronized(lock) {
        // Pod zámkem jako incrementUnread/markRead: obnova ze zálohy může běžet
        // souběžně s incrementUnread z poll smyčky a bez zámku by si zápisy
        // read-modify-write počítadla přepsaly (odznak by ukázal špatné číslo).
        prefs.edit().putInt(unreadKey(contactId), count.coerceAtLeast(0)).apply()
    }

    /** Přepíše celou historii konverzace (použije se při obnově ze zálohy). */
    fun restore(contactId: String, messages: List<ChatMessage>): Boolean = synchronized(lock) {
        // Duplicitní id by shodila obrazovku chatu (LazyColumn je klíčovaný id),
        // a záloha může přijít odkudkoli - proto se tady odfiltrují.
        saveLocked(contactId, messages.distinctBy { it.id })
    }

    /**
     * Sloučí historii ze zálohy se STÁVAJÍCÍ (na rozdíl od [restore], které
     * přepisuje). Existující zprávy zůstanou v živé podobě; ze zálohy se doplní
     * jen ty, co v konverzaci ještě nejsou (podle `id` i `wireId`), a výsledek se
     * seřadí podle času. Tím obnova nikdy nezahodí zprávy přijaté PO vytvoření
     * zálohy - dřív [restore] celou konverzaci přepsal a novější zprávy zmizely.
     *
     * Když stávající historii nejde přečíst, spadne zpět na přepis (lepší obnovit
     * aspoň zálohu než nic).
     */
    fun restoreMerging(contactId: String, backup: List<ChatMessage>): Boolean = synchronized(lock) {
        val existing = loadForWriteLocked(contactId)
            ?: return@synchronized saveLocked(contactId, backup.distinctBy { it.id })
        val seenIds = existing.mapTo(HashSet()) { it.id }
        val seenWire = existing.mapNotNullTo(HashSet()) { it.wireId }
        val merged = ArrayList(existing)
        for (m in backup) {
            if (m.id in seenIds || (m.wireId != null && m.wireId in seenWire)) {
                // Duplicitní zpráva ze zálohy se nepřidá. Její médium (fotka) se
                // ale při importu čerstvě uložilo na disk - když se zpráva zahodí,
                // soubor by osiřel. Ukliď ho (existující zpráva má vlastní kopii).
                m.mediaPath?.let { runCatching { File(it).delete() } }
                continue
            }
            seenIds.add(m.id)
            m.wireId?.let { seenWire.add(it) }
            merged.add(m)
        }
        merged.sortBy { it.timestamp }
        saveLocked(contactId, merged)
    }

    /** Smaže celou historii konverzace (např. při smazání kontaktu). */
    fun clear(contactId: String) = synchronized(lock) {
        cache.remove(contactId)
        prefs.edit().remove(key(contactId)).remove(unreadKey(contactId)).apply()
    }

    private fun unreadKey(contactId: String) = "unread_$contactId"

    /** Uloží historii a zaktualizuje cache. Volej jen pod [lock]. */
    private fun saveLocked(contactId: String, messages: List<ChatMessage>): Boolean {
        return try {
            val array = JSONArray()
            messages.forEach { m ->
                array.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("out", m.outgoing)
                        .put("txt", m.text)
                        .put("ts", m.timestamp)
                        .put("st", m.status.name)
                        .put("kind", m.kind.name)
                        .apply {
                            m.mediaPath?.let { put("media", it) }
                            m.mimeType?.let { put("mime", it) }
                            m.wireId?.let { put("wid", it) }
                            m.replyToWireId?.let { put("rto", it) }
                            m.editedAt?.let { put("ed", it) }
                            if (m.deleted) put("del", true)
                            if (m.reactions.isNotEmpty()) put("rx", writeReactions(m.reactions))
                        }
                )
            }
            val encrypted = crypto.encrypt(array.toString())
            // commit(), ne apply(): příchozí zprávu už relay smazal, takže
            // asynchronní zápis by ji při zabití procesu ztratil nenávratně.
            // Jsme na IO vlákně, takže synchronní zápis nikoho neblokuje.
            prefs.edit().putString(key(contactId), encrypted).commit()
            cache[contactId] = messages
            _changes.tryEmit(contactId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nepodařilo se uložit historii (${e.javaClass.simpleName})")
            // Cache po neúspěšném zápisu zahoď, ať v paměti nezůstane stav, který
            // na disku není - příště se načte znovu z disku.
            cache.remove(contactId)
            false
        }
    }

    /** Reakce z JSONu. Poškozený záznam se přeskočí, historii kvůli němu neztratíme. */
    private fun readReactions(obj: JSONObject?): Map<String, ChatMessage.Reaction> {
        if (obj == null) return emptyMap()
        val out = HashMap<String, ChatMessage.Reaction>(2)
        for (reactor in obj.keys()) {
            val r = obj.optJSONObject(reactor) ?: continue
            // Prázdné emoji je platný záznam - náhrobek po zrušené reakci.
            // Zahodit ho by znamenalo ztratit čas zrušení (viz setReaction).
            out[reactor] = ChatMessage.Reaction(r.optString("e"), r.optLong("t"))
        }
        return out
    }

    private fun writeReactions(reactions: Map<String, ChatMessage.Reaction>): JSONObject {
        val obj = JSONObject()
        reactions.forEach { (reactor, r) ->
            obj.put(reactor, JSONObject().put("e", r.emoji).put("t", r.timestamp))
        }
        return obj
    }

    private fun key(contactId: String) = "msgs_$contactId"

    companion object {
        private const val PREFS_NAME = "crypto_chat_messages"
        private const val TAG = "ChatRepository"

        /** Procesový zámek nad celou historií (viz poznámka o souběhu v třídě). */
        private val lock = Any()

        /** Rozparsovaná historie v paměti, klíčovaná id kontaktu. */
        private val cache = HashMap<String, List<ChatMessage>>()

        private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

        /**
         * Ohlásí id kontaktu, jehož historie se právě změnila. Zprávy vyzvedává
         * ze sítě foreground service, ale zobrazuje je `ChatScreen` - bez tohohle
         * signálu by se otevřená konverzace o nové zprávě dozvěděla až se
         * zpožděním (nebo vůbec).
         */
        val changes = _changes.asSharedFlow()

        /**
         * Zahodí paměťovou cache. **Jen pro testy** - ty běží v jednom procesu
         * za sebou a bez tohohle by si historie z předchozího testu přenesla
         * do dalšího.
         */
        fun resetCacheForTests() = synchronized(lock) { cache.clear() }
    }
}
