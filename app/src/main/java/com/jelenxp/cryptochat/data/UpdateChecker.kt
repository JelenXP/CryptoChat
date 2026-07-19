package com.jelenxp.cryptochat.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zjišťuje, jestli je na GitHub Releases novější verze appky. Používá veřejné
 * GitHub API - dotaz neposílá žádná uživatelská data, jen si stáhne seznam
 * vydání. Nikdy nevyhodí výjimku (offline, chyba sítě → vrátí `null`), aby
 * kontrola nikdy nezablokovala ani neshodila appku.
 *
 * Verze je „důležitá" (`important`), pokud její poznámky (release body)
 * obsahují značku [IMPORTANT_MARKER] - tu do vydání přidává release workflow
 * podle zprávy gitového tagu.
 */
object UpdateChecker {

    private const val RELEASES_URL = "https://api.github.com/repos/JelenXP/CryptoChat/releases"
    private const val IMPORTANT_MARKER = "[important]"
    private const val TIMEOUT_MS = 6000

    /**
     * @param latestVersion nejnovější dostupná verze (bez „v", např. „2.2").
     * @param latestUrl odkaz na nejnovější vydání (vždy to nejnovější, i když
     *   „important" byla některá starší mezi tím).
     * @param important je mezi verzemi novějšími než ta nainstalovaná některá
     *   označená jako důležitá?
     */
    data class UpdateInfo(
        val latestVersion: String,
        val latestUrl: String,
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
            val json = fetch(RELEASES_URL) ?: return Result.Failed
            val array = JSONArray(json)
            var latest: Release? = null
            var importantNewer = false
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) continue
                val version = obj.optString("tag_name").removePrefix("v").trim()
                if (version.isEmpty()) continue
                val body = obj.optString("body")
                val release = Release(version, obj.optString("html_url"), body)
                if (latest == null || compareVersions(release.version, latest.version) > 0) {
                    latest = release
                }
                if (compareVersions(release.version, currentVersion) > 0 &&
                    body.contains(IMPORTANT_MARKER, ignoreCase = true)
                ) {
                    importantNewer = true
                }
            }
            // Fetch prošel, ale nenašla se žádná platná verze → nic novějšího.
            val newest = latest ?: return Result.UpToDate
            if (compareVersions(newest.version, currentVersion) <= 0) return Result.UpToDate
            Result.UpdateAvailable(UpdateInfo(newest.version, newest.url, importantNewer))
        } catch (e: Exception) {
            Result.Failed
        }
    }

    private data class Release(val version: String, val url: String, val body: String)

    private fun fetch(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CryptoChat-UpdateCheck")
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
