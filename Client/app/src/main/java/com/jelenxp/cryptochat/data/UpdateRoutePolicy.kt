package com.jelenxp.cryptochat.data

/**
 * Přes co poslat kontrolu aktualizací (GitHub) podle EFEKTIVNÍ adresy relaye.
 *
 * Rozhoduje o SOUKROMÍ: přímý (clearnet) požadavek na GitHub z reálné IP prozradí,
 * že tenhle privacy messenger na zařízení běží - přesně tomu má routování přes Tor
 * bránit. Napřímo se proto jde JEN když uživatel clearnet transport VÝSLOVNĚ zvolil
 * (efektivní adresa je http/https, tj. Cloudflare výchozí nebo vlastní clearnet
 * adresa) - tam už stejnou IP vidí relay i tak, takže kontrola aktualizací nic
 * navíc neprozradí.
 *
 * Klíčová oprava (audit 2026-07-31): **prázdná** efektivní adresa (vlastní režim +
 * nevyplněná URL = chat vypnutý) NENÍ volba clearnetu - dřív z ní `viaTor=false`
 * poslal kontrolu napřímo a IP unikla, aniž o clearnetu uživatel rozhodl. Nově se
 * v tom stavu kontrola PŘESKOČÍ ([Route.SKIP]).
 *
 * Čistá funkce (pro test bez sítě); onion se pozná přes `TorManager.urlIsOnion` a
 * předá jako [isOnion], ať tahle vrstva nezávisí na balíčku `chat`.
 */
object UpdateRoutePolicy {

    enum class Route { TOR, DIRECT, SKIP }

    /**
     * @param effectiveRelayUrl efektivní adresa relaye ([SettingsRepository.getRelayUrl]).
     * @param isOnion je [effectiveRelayUrl] .onion? (`TorManager.urlIsOnion`)
     */
    fun route(effectiveRelayUrl: String, isOnion: Boolean): Route = when {
        effectiveRelayUrl.isBlank() -> Route.SKIP   // chat vypnutý → neriskuj clearnet leak
        isOnion -> Route.TOR
        else -> Route.DIRECT                         // uživatel zvolil clearnet transport
    }
}
