package com.jelenxp.cryptochat.data

import com.jelenxp.cryptochat.chat.TorManager
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Zjišťuje, jestli je na GitHub Releases novější verze appky. Používá veřejné
 * GitHub API - dotaz neposílá žádná uživatelská data, jen si stáhne seznam
 * vydání. Nikdy nevyhodí výjimku (offline, chyba sítě → vrátí `null`), aby
 * kontrola nikdy nezablokovala ani neshodila appku.
 *
 * **Dotaz jde vždy přes zabudovaný Tor.** Napřímo by to byl jediný požadavek
 * celé appky, který by z reálné IP prozradil, že tenhle (privacy) messenger
 * na daném zařízení běží a jak často se spouští - přesně ta metadata, kvůli
 * kterým appka jinak jede přes onion. Když Tor neběží, kontrola se prostě
 * přeskočí ([Result.Failed]) a zkusí se příště; nikdy se nepošle napřímo.
 *
 * Verze je „důležitá" (`important`), pokud její poznámky (release body)
 * obsahují značku [IMPORTANT_MARKER] - tu do vydání přidává release workflow
 * podle zprávy gitového tagu.
 */
object UpdateChecker {

    // Veřejné repozitáře s vydanými APK. Zkouší se VŠECHNY a bere se nejnovější
    // verze napříč nimi - kvůli plynulému přechodu při přejmenování / opensourcingu:
    //  - CryptoChatServer-releases: současný zdroj (releases repo se ZATÍM
    //    nepřejmenovává, ať se nerozbije updator už nainstalovaných verzí),
    //  - CryptoChat-releases: budoucí vyhrazený releases repo,
    //  - CryptoChat: samotný (časem opensource) repo chat appky, kdyby releasy
    //    šly rovnou tam.
    // Kód chat appky je zatím privátní v JelenXP/CryptoChat (dřív CryptoChatOnline).
    private val RELEASES_URLS = listOf(
        "https://api.github.com/repos/JelenXP/CryptoChatServer-releases/releases",
        "https://api.github.com/repos/JelenXP/CryptoChat-releases/releases",
        "https://api.github.com/repos/JelenXP/CryptoChat/releases"
    )
    private const val IMPORTANT_MARKER = "[important]"

    // Přes Tor je latence vyšší než napřímo, takže velkorysejší timeouty.
    private const val TIMEOUT_MS = 20_000

    /** Jak dlouho čekat, než zabudovaný Tor otevře SOCKS listener. */
    private const val TOR_WAIT_MS = 30_000L

    /**
     * @param latestVersion nejnovější dostupná verze (bez „v", např. „2.2").
     * @param latestUrl odkaz na nejnovější vydání (vždy to nejnovější, i když
     *   „important" byla některá starší mezi tím).
     * @param important je mezi verzemi novějšími než ta nainstalovaná některá
     *   označená jako důležitá?
     * @param notes novinky VŠECH verzí novějších než nainstalovaná (nejnovější
     *   první). Díky tomu člověk, který přeskočí verzi (např. 2.2.0 → rovnou
     *   3.0.1 bez 3.0.0), uvidí i novinky té přeskočené - ne jen poslední verze.
     */
    data class UpdateInfo(
        val latestVersion: String,
        val latestUrl: String,
        val important: Boolean,
        val notes: List<ReleaseNote> = emptyList()
    )

    /**
     * Novinky jedné novejší verze (z release body na GitHubu). [important] je
     * odvozeno z [IMPORTANT_MARKER] v těle; ze zobrazeného [body] je marker
     * odstraněn (je určený jen appce, ne ke čtení).
     */
    data class ReleaseNote(
        val version: String,
        val body: String,
        val important: Boolean
    )

    /**
     * Výsledek kontroly pro ruční spuštění z Nastavení, který na rozdíl od
     * [check] rozlišuje „jste aktuální" od „nepovedlo se" (offline apod.).
     */
    sealed interface Result {
        /** Je novější verze. */
        data class UpdateAvailable(val info: UpdateInfo) : Result
        /** Nic novějšího není. */
        data object UpToDate : Result
        /** Kontrola se nepovedla (offline, chyba serveru…). */
        data object Failed : Result
    }

    /** Vrátí info o novější verzi, nebo `null` (nic novějšího / se to nepovedlo). */
    fun check(currentVersion: String): UpdateInfo? =
        (checkDetailed(currentVersion) as? Result.UpdateAvailable)?.info

    /**
     * Podrobná varianta [check]: vrátí [Result] rozlišující dostupný update,
     * aktuální verzi a selhání. Nikdy nevyhodí výjimku.
     */
    fun checkDetailed(currentVersion: String): Result {
        return try {
            var reachedAny = false
            val releases = mutableListOf<Release>()
            for (url in RELEASES_URLS) {
                // null = zdroj nedosažitelný (síť / neexistující repo → 404).
                // Přeskoč ho; verze sbíráme napříč zbylými.
                val json = fetch(url) ?: continue
                reachedAny = true
                val array = try { JSONArray(json) } catch (e: Exception) { continue }
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) continue
                    val version = obj.optString("tag_name").removePrefix("v").trim()
                    if (version.isEmpty()) continue
                    releases.add(Release(version, obj.optString("html_url"), obj.optString("body")))
                }
            }
            // Žádný zdroj neodpověděl (offline / všechny 404) → jako selhání.
            if (!reachedAny) return Result.Failed
            // Zdroj(e) odpověděly, ale nic novějšího → jste aktuální.
            val info = buildUpdateInfo(releases, currentVersion) ?: return Result.UpToDate
            Result.UpdateAvailable(info)
        } catch (e: Exception) {
            Result.Failed
        }
    }

    /**
     * Z posbíraných vydání (napříč všemi repos) poskládá [UpdateInfo]: jen verze
     * novejší než [currentVersion], odshora nejnovější. Stejná verze z víc repos
     * se sloučí (přednost má neprázdné tělo). Vrátí `null`, když nic novejšího
     * není. Čistá funkce (bez sítě) - proto testovatelná.
     */
    internal fun buildUpdateInfo(releases: List<Release>, currentVersion: String): UpdateInfo? {
        // Dedup podle verze (stejné vydání může být ve víc repos); prefer neprázdné tělo.
        val byVersion = LinkedHashMap<String, Release>()
        for (r in releases) {
            if (compareVersions(r.version, currentVersion) <= 0) continue
            val existing = byVersion[r.version]
            if (existing == null || (existing.body.isBlank() && r.body.isNotBlank())) {
                byVersion[r.version] = r
            }
        }
        if (byVersion.isEmpty()) return null
        // Nejnovější první.
        val sorted = byVersion.values.sortedWith { a, b -> compareVersions(b.version, a.version) }
        val notes = sorted.map { r ->
            ReleaseNote(
                version = r.version,
                body = stripImportantMarker(r.body),
                important = r.body.contains(IMPORTANT_MARKER, ignoreCase = true)
            )
        }
        val latest = sorted.first()
        return UpdateInfo(
            latestVersion = latest.version,
            latestUrl = latest.url,
            important = notes.any { it.important },
            notes = notes
        )
    }

    /** Odebere [IMPORTANT_MARKER] z těla novinek (marker je jen pro appku, ne ke čtení). */
    internal fun stripImportantMarker(body: String): String =
        body.replace(Regex(Regex.escape(IMPORTANT_MARKER), RegexOption.IGNORE_CASE), "").trim()

    internal data class Release(val version: String, val url: String, val body: String)

    private fun fetch(urlString: String): String? {
        // Bez běžícího Toru se nekontroluje vůbec - radši žádná kontrola než
        // dotaz z reálné IP (viz poznámka u třídy).
        if (!TorManager.awaitReady(TOR_WAIT_MS)) return null
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(TorManager.socksHost, TorManager.socksPort)
        )
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                // Neutrální User-Agent: konkrétní název appky by i přes Tor
                // prozradil, o jaký software jde (GitHub API UA vyžaduje).
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Porovná verze po číselných složkách (např. „2.10" > „2.9", „2.2" > „2.1").
     * Vrátí >0 když a>b, <0 když a<b, 0 když jsou stejné.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
