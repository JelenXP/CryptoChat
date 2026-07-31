package com.jelenxp.cryptochat.data

/**
 * Rozhodování o kontrole nové verze na pozadí - **čistě funkčně, bez Androidu**.
 *
 * Je to schválně oddělené od [com.jelenxp.cryptochat.chat.TorForegroundService]:
 * uvnitř služby by tahle pravidla nešla otestovat (potřebuje běžící službu, Tor
 * a `SharedPreferences`) a chyby by se v nich hledaly ručně. Tady jsou to obyčejné
 * funkce nad čísly a řetězci, takže je pokrývá `UpdateNotifyPolicyTest`.
 */
object UpdateNotifyPolicy {

    /** Co má služba udělat v tomhle tiku hlídače. */
    enum class Decision {
        /** Jít na síť. */
        CHECK,

        /** Na síť nechodit, jen si poznamenat čas (první spuštění). */
        STAMP_ONLY,

        /** Neudělat nic. */
        SKIP
    }

    /**
     * Má se teď kontrolovat nová verze?
     *
     * [lastCheckAt] `0` znamená čerstvou instalaci. Tehdy se **na síť nechodí** -
     * první tik hlídače padne do chvíle, kdy Tor teprve bootstrapuje, běží
     * zahřívání a `MainActivity` dělá svou vlastní kontrolu. Dva požadavky přes
     * studený okruh naráz nikomu neprospějí, takže se jen orazítkuje čas.
     *
     * Hodiny posunuté dozadu ([lastCheckAt] v budoucnosti) vedou na jednu
     * kontrolu navíc, po které se razítko srovná - lepší než čekat, až reálný
     * čas dožene tu nesmyslnou hodnotu.
     */
    fun decide(now: Long, lastCheckAt: Long, intervalMs: Long): Decision = when {
        lastCheckAt <= 0L -> Decision.STAMP_ONLY
        lastCheckAt > now -> Decision.CHECK
        now - lastCheckAt >= intervalMs -> Decision.CHECK
        else -> Decision.SKIP
    }

    /**
     * Razítko, které se má zapsat po NEÚSPĚŠNÉ kontrole, aby další pokus přišel
     * za exponenciálně rostoucí dobu (`base`, `2×base`, `4×base`…), nejvýš ale
     * za celý [intervalMs].
     *
     * Nedostupný relay je tady běžný stav (uspaný notebook), takže konstantní
     * opakování by znamenalo desítky marných pokusů denně - a každý čeká na Tor.
     * Invariant projektu zní „selhání MUSÍ mít backoff".
     */
    fun retryStamp(now: Long, intervalMs: Long, baseRetryMs: Long, failures: Int): Long {
        val steps = (failures - 1).coerceIn(0, 20)
        // Dolní mez nesmí přerůst horní, jinak `coerceIn(min, max)` vyhodí výjimku.
        // Dnes nedosažitelné (base 30 min < interval 6 h), ale kdyby někdo posunul
        // konstanty, spadlo by to TIŠE v korutině služby - proto pojistka i test.
        val hi = intervalMs
        val lo = baseRetryMs.coerceAtMost(hi)
        // Násobí se v Long a strop se uplatní hned, takže nemůže přetéct.
        val delay = (baseRetryMs shl steps).coerceIn(lo, hi)
        return now - intervalMs + delay
    }

    /**
     * Má se o téhle verzi poslat notifikace?
     *
     * Pravidla musí ctít, co si uživatel odklikl v aplikaci - jinak by dostal
     * z pozadí přesně to, co před chvílí zavřel:
     *  - o jedné verzi upozorňujeme **nejvýš jednou** ([notifiedVersion]),
     *  - verzi, kterou uživatel v appce zavřel ([dismissedVersion]), už
     *    nepřipomínáme vůbec - i kdyby byla označená jako důležitá; o té verzi
     *    přece ví.
     */
    fun shouldNotify(
        latestVersion: String,
        important: Boolean,
        notifiedVersion: String?,
        dismissedVersion: String?
    ): Boolean {
        if (latestVersion.isBlank()) return false
        if (notifiedVersion == latestVersion) return false
        if (dismissedVersion == latestVersion) return false
        return true
    }
}
