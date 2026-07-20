package com.jelenxp.cryptochat.diagnostics

import android.content.Context
import android.os.Build
import com.jelenxp.cryptochat.chat.RelayClient
import com.jelenxp.cryptochat.chat.RelayStatus
import com.jelenxp.cryptochat.chat.TorController
import com.jelenxp.cryptochat.chat.TorManager
import com.jelenxp.cryptochat.data.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dobrovolné hlášení chyby: složí JSON s tím, co uživatel odsouhlasil, a pošle
 * ho jako `POST /report` na nastavený relay.
 *
 * **Soukromí je tady důležitější než diagnostika:**
 *  - odesílá se JEN to, co uživatel zaškrtl, a předem si to může přečíst
 *    ([buildPreview] ukazuje přesně to, co se pošle),
 *  - do hlášení se nikdy nedostane obsah zpráv, klíče, jména kontaktů,
 *    ID schránek ani adresa relaye (viz [DiagnosticsLog]),
 *  - přenos jde stejnou cestou jako zprávy - **přes zabudovaný Tor**. Napřímo
 *    by se odeslal jediný požadavek appky z reálné IP a prozradil, že tenhle
 *    messenger na daném zařízení běží. Když Tor nenaběhne, hlášení se
 *    NEODEŠLE ([Error.TOR_NOT_READY]), nikdy se nezkusí obejít.
 *
 * Nikdy nevyhodí výjimku - všechno končí jako [Result].
 */
object BugReporter {

    private const val TAG = "BugReporter"

    /** Cesta na relayi, která hlášení přijímá (viz `server.py`). */
    private const val REPORT_PATH = "/report"

    /** Verze formátu hlášení - ať server pozná, co mu přišlo. */
    private const val REPORT_VERSION = 1

    /** Strop délky popisu od uživatele. */
    const val MAX_DESCRIPTION_CHARS = 4000

    /** Kolik znaků záznamu o pádu se přiloží (bere se KONEC souboru - ten je čerstvý). */
    private const val MAX_CRASH_CHARS = 8000

    /** Strop velikosti těla. Server bere 256 KB, necháváme rezervu na hlavičky. */
    private const val MAX_BODY_BYTES = 200 * 1024

    /** Jak dlouho čekat, než zabudovaný Tor otevře SOCKS listener. */
    private const val TOR_WAIT_MS = 60_000L

    /** Soubor s posledním pádem (zapisuje ho `CryptoChatApplication`). */
    private const val CRASH_LOG_FILE = "crash_log.txt"

    /** Co všechno se k popisu přiloží. Každá položka je v UI vypnutelná. */
    data class Options(
        val device: Boolean = true,
        val diagnostics: Boolean = true,
        val connection: Boolean = true,
        val crash: Boolean = false
    )

    /** Důvod neúspěchu - obrazovka si k němu dohledá hlášku v obou jazycích. */
    enum class Error { NO_RELAY, TOR_NOT_READY, NETWORK, SERVER_REJECTED, TOO_LARGE }

    sealed interface Result {
        data object Success : Result
        data class Failed(val error: Error) : Result
    }

    /** Je vůbec co přiložit u volby „záznam o posledním pádu"? */
    fun hasCrashLog(context: Context): Boolean = try {
        crashFile(context).let { it.isFile && it.length() > 0 }
    } catch (e: Exception) {
        false
    }

    /**
     * Čitelný náhled toho, co se odešle (odsazený JSON). Uživatel si ho může
     * přečíst PŘED odesláním - je to přesně stejný obsah, jen naformátovaný.
     */
    fun buildPreview(context: Context, description: String, options: Options): String = try {
        buildReport(context, description, options).toString(2)
    } catch (e: Exception) {
        // Náhled je jen pomůcka - když selže, ať to nezablokuje obrazovku.
        ""
    }

    /**
     * Odešle hlášení. **Blokuje** (síť) - volej z IO dispatcheru. Když je popis
     * prázdný nebo není nastavený server, vrátí chybu a nic neposílá.
     */
    fun send(context: Context, description: String, options: Options): Result {
        return try {
            val baseUrl = SettingsRepository(context).getRelayUrl()
            if (baseUrl.isBlank()) return Result.Failed(Error.NO_RELAY)

            val body = encodeWithinLimit(context, description, options)
                ?: return Result.Failed(Error.TOO_LARGE)

            // Onion cíl = musí běžet Tor. Bez něj se NEODESÍLÁ (viz komentář třídy).
            if (TorManager.isOnion(hostOf(baseUrl))) {
                TorController.ensureStarted(context)
                if (!TorManager.awaitReady(TOR_WAIT_MS)) {
                    DiagnosticsLog.warn(TAG, "hlášení neodesláno - Tor není připravený")
                    return Result.Failed(Error.TOR_NOT_READY)
                }
            }

            val code = RelayClient.postJson(baseUrl, REPORT_PATH, body)
            DiagnosticsLog.log(TAG, "hlášení chyby odesláno -> HTTP $code")
            when {
                code in 200..204 -> Result.Success
                code == 413 -> Result.Failed(Error.TOO_LARGE)
                else -> Result.Failed(Error.SERVER_REJECTED)
            }
        } catch (e: Exception) {
            // Typ výjimky stačí; její text může obsahovat cílovou adresu.
            DiagnosticsLog.warn(TAG, "odeslání hlášení selhalo (${e.javaClass.simpleName})")
            Result.Failed(Error.NETWORK)
        }
    }

    /**
     * Zakóduje hlášení do UTF-8 a vejde se do [MAX_BODY_BYTES]. Když je moc velké,
     * postupně ubírá to nejobjemnější (pád, pak starší řádky logu) - ať se hlášení
     * pošle aspoň zkrácené, místo aby se neposlalo vůbec. `null` = ani po zkrácení
     * se nevejde (extrémně dlouhý popis).
     */
    private fun encodeWithinLimit(
        context: Context,
        description: String,
        options: Options
    ): ByteArray? {
        val fallbacks = listOf(
            options,
            options.copy(crash = false),
            options.copy(crash = false, diagnostics = false)
        )
        for (variant in fallbacks) {
            val bytes = buildReport(context, description, variant)
                .toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_BODY_BYTES) return bytes
        }
        return null
    }

    /** Složí JSON hlášení podle zaškrtnutých voleb. */
    private fun buildReport(
        context: Context,
        description: String,
        options: Options
    ): JSONObject {
        val root = JSONObject()
        root.put("report_version", REPORT_VERSION)
        root.put("created_at", System.currentTimeMillis())
        root.put("description", description.trim().take(MAX_DESCRIPTION_CHARS))

        if (options.device) {
            root.put("device", JSONObject().apply {
                put("app_version", appVersion(context))
                put("android_release", Build.VERSION.RELEASE ?: "")
                put("android_sdk", Build.VERSION.SDK_INT)
                put("manufacturer", Build.MANUFACTURER ?: "")
                put("model", Build.MODEL ?: "")
            })
        }

        if (options.connection) {
            // Vědomě BEZ adresy relaye - jen jestli je onion a jestli je vlastní.
            val settings = SettingsRepository(context)
            root.put("connection", JSONObject().apply {
                put("tor_ready", TorManager.ready)
                put("relay_state", RelayStatus.state.name)
                put("relay_onion", TorManager.isOnion(hostOf(settings.getRelayUrl())))
                put("relay_custom", settings.isUsingCustomRelay())
            })
        }

        if (options.diagnostics) {
            root.put("diagnostics", JSONArray().apply {
                DiagnosticsLog.dumpLines().forEach { put(it) }
            })
        }

        if (options.crash) {
            readCrashLog(context)?.let { root.put("crash_log", it) }
        }
        return root
    }

    /** Verze appky (`versionName`); prázdný řetězec, když se nedá zjistit. */
    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun crashFile(context: Context) = File(context.filesDir, CRASH_LOG_FILE)

    /** Konec souboru s posledním pádem, nebo `null` (žádný pád / nejde přečíst). */
    /**
     * Záznam o posledním pádu. Vzniká z plného `printStackTrace`, takže může
     * obsahovat cesty k souborům, názvy příloh nebo `.onion` adresu - a míří na
     * NEDŮVĚRYHODNÝ server. Než odejde, projde stejnou redakcí jako diagnostika
     * a navíc se z něj vyříznou cesty do soukromého úložiště appky.
     */
    private fun readCrashLog(context: Context): String? = try {
        val file = crashFile(context)
        if (!file.isFile) {
            null
        } else {
            DiagnosticsLog.redact(file.readText().takeLast(MAX_CRASH_CHARS))
                .replace(Regex("/data/(user/\\d+|data)/[^\\s)]*"), "<cesta>")
                .replace(Regex("/storage/[^\\s)]*"), "<cesta>")
                .replace(Regex("content://[^\\s)]*"), "<uri>")
        }
    } catch (e: Exception) {
        null
    }

    /** Host z adresy relaye (prázdný, když je adresa nesmyslná). */
    private fun hostOf(baseUrl: String): String = try {
        java.net.URI(baseUrl.trim()).host.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
