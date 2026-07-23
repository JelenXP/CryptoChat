package com.jelenxp.cryptochat.chat

import android.util.Log
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL

/**
 * Minimální HTTP klient k relayi („slepé schránce"). Umí dvě cesty:
 *  - **napřímo** přes `HttpURLConnection` (http/https adresy),
 *  - **přes Tor** vlastním SOCKS5 tunelem, když je cíl `.onion` adresa.
 *
 * Používá jen standardní knihovnu - žádné závislosti navíc (SOCKS5 i HTTP přes
 * tunel jsou napsané ručně; `.onion` se rozřeší až v Toru, ne lokálně).
 *
 * Kontrakt serveru (viz CryptoChatServer/server.py):
 *  - PUT /m/<id>  telo = syrové bajty blobu -> 2xx
 *  - GET /m/<id>  -> 200 + délkově rámované bloby ([4B BE délka][data]...), nebo 204 když prázdno
 *
 * Metody blokují (I/O) - volej z IO dispatcheru. Na síťovou chybu vyhodí IOException.
 */
object RelayClient {

    private const val TAG = "RelayClient"

    private const val TIMEOUT_MS = 30_000

    // Navázání onion okruhu (SOCKS connect + čekání na rendezvous) je zvlášť od
    // čtení odpovědi. První připojení na službu bývá pomalé - proto radši KRATŠÍ
    // connect timeout a VÍC pokusů: jakmile Tor stáhne deskriptor služby, další
    // pokus se „chytne" hned, místo aby první visel na plném timeoutu.
    /** Timeout na navázání onion okruhu (opakovaný pokus). */
    private const val ONION_CONNECT_TIMEOUT_MS = 18_000

    /**
     * Timeout PRVNÍHO pokusu. Studený Tor musí stáhnout deskriptor onion služby
     * a domluvit rendezvous - to běžně trvá déle než 18 s. Utnout ho a zkoušet
     * znovu je horší než počkat: každý další pokus začíná od nuly.
     */
    private const val ONION_FIRST_CONNECT_TIMEOUT_MS = 45_000

    /**
     * Výchozí timeout na čtení odpovědi (u long-pollu se prodlouží). Delší kvůli
     * fotkám a kouskům souborů - blob může mít až ~2 MB a přes Tor to chvíli trvá.
     */
    private const val ONION_READ_TIMEOUT_MS = 60_000

    /**
     * Strop na velikost odpovědi relaye.
     *
     * Server sám vrací nejvýš ~8 MB na jedno vyzvednutí (zbytek pošle v dalším
     * kole), takže víc není potřeba. Vyšší hodnota by byla nebezpečná: buffer se
     * při `toByteArray()` zkopíruje, takže špička je dvojnásobek - u 64 MB by to
     * bylo ~128 MB a nepřátelský server by službu položil právě tím limitem,
     * který ji má chránit.
     */
    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    /** Content-Type pro [postJson]. */
    private const val JSON_CONTENT_TYPE = "application/json"

    /** Výchozí Content-Type (syrové bloby zpráv). */
    private const val OCTET_CONTENT_TYPE = "application/octet-stream"

    /** Kolikrát zkusit navázat onion okruh, než to vzdáme. */
    private const val ONION_ATTEMPTS = 5

    /**
     * Pokusy u long-pollu. Míň než [ONION_ATTEMPTS]: příjem se stejně hned opakuje
     * v další smyčce, takže vytrvalost je tu zbytečná - a při nedostupném serveru
     * by 5 × 18 s znamenalo minuty a půl marného stavění okruhů (a vybité baterie).
     */
    private const val ONION_POLL_ATTEMPTS = 2

    /** Pauza mezi pokusy onion požadavku. */
    private const val ONION_RETRY_DELAY_MS = 700L

    /** Jak dlouho čekat, než zabudovaný Tor otevře SOCKS listener (bootstrap). */
    private const val TOR_READY_TIMEOUT_MS = 60_000L

    /** Uloží blob do schránky. Vrací true při úspěchu (2xx). */
    fun put(baseUrl: String, mailboxId: String, blob: ByteArray): Boolean {
        val target = Target.parse(baseUrl, "/m/$mailboxId")
        val ok = if (TorManager.isOnion(target.host)) {
            onionRequest(target, "PUT", blob, ONION_READ_TIMEOUT_MS, isolation = mailboxId).code in 200..204
        } else {
            directPut(baseUrl, mailboxId, blob)
        }
        // Jen velikost a výsledek - ID schránky se do diagnostiky NIKDY nedostane.
        DiagnosticsLog.log(TAG, "odeslán blob ${blob.size} B: ${if (ok) "ok" else "odmítnuto"}")
        return ok
    }

    /**
     * Pošle JSON tělo metodou POST na danou cestu relaye (mimo schránky). Používá
     * to jen dobrovolné hlášení chyby (`/report`) - jde stejnou cestou jako zprávy,
     * tedy přes Tor, aby se neprozradila reálná IP uživatele. Vrací HTTP kód;
     * síťová chyba je IOException.
     */
    fun postJson(baseUrl: String, path: String, json: ByteArray): Int {
        val target = Target.parse(baseUrl, path)
        return if (TorManager.isOnion(target.host)) {
            onionRequest(target, "POST", json, ONION_READ_TIMEOUT_MS, contentType = JSON_CONTENT_TYPE, isolation = "report").code
        } else {
            val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(json.size)
                setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
            }
            try {
                conn.outputStream.use { it.write(json) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Vyzvednuté bloby a pořadové číslo, kterým se má příjem potvrdit ([ack]).
     * [ackSeq] je -1, když není co potvrzovat.
     */
    data class Fetched(val blobs: List<ByteArray>, val ackSeq: Long)

    /**
     * Vyzvedne čekající bloby. Server je NEsmaže - to udělá až [ack] poté, co jsou
     * bezpečně uložené. Prázdná schránka = prázdný seznam. Když [waitSeconds] > 0,
     * použije se long-polling: server drží spojení otevřené, dokud nedorazí zpráva
     * (nebo tolik sekund) - zprávy tak chodí skoro okamžitě a přes Tor jde míň spojení.
     */

    fun get(baseUrl: String, mailboxId: String, waitSeconds: Int = 0): Fetched {
        // ack=1: server bloby NEsmaže, jen je vrátí i s pořadovým číslem. Smažou
        // se až potvrzením, tedy až je máme bezpečně uložené. Kdyby se spojení
        // rozpadlo uprostřed odpovědi (rozpadlý Tor okruh, přepnutá síť), zůstanou
        // ležet a dorazí příště - dřív by v takové chvíli byly nenávratně pryč.
        val query = if (waitSeconds > 0) "?ack=1&wait=$waitSeconds" else "?ack=1"
        val target = Target.parse(baseUrl, "/m/$mailboxId$query")
        val fetched = if (TorManager.isOnion(target.host)) {
            // U long-pollu server drží spojení až waitSeconds - čtecí timeout
            // musí být o kus delší, ať ho nepřerušíme dřív než server odpoví.
            val readTimeout = if (waitSeconds > 0) waitSeconds * 1000 + 10_000 else ONION_READ_TIMEOUT_MS
            val attempts = if (waitSeconds > 0) ONION_POLL_ATTEMPTS else ONION_ATTEMPTS
            val response = onionRequest(target, "GET", null, readTimeout, attempts, isolation = mailboxId)
            when (response.code) {
                204 -> Fetched(emptyList(), -1)
                200 -> Fetched(unframe(response.body), response.seq())
                else -> throw IOException("Relay GET vrátil HTTP ${response.code}")
            }
        } else {
            directGet(baseUrl, mailboxId, query)
        }
        if (fetched.blobs.isNotEmpty()) {
            DiagnosticsLog.log(TAG, "vyzvednuto ${fetched.blobs.size} blobů")
        }
        return fetched
    }

    /**
     * Potvrdí, že bloby až po [seq] jsou bezpečně uložené - server je teprve teď
     * smaže. Vrací úspěch; při neúspěchu se prostě doručí znovu (klient si je
     * odfiltruje podle otisků, viz [ReplayGuard]).
     */
    fun ack(baseUrl: String, mailboxId: String, seq: Long): Boolean {
        if (seq < 0) return true
        val target = Target.parse(baseUrl, "/m/$mailboxId?upto=$seq")
        val acked = try {
            if (TorManager.isOnion(target.host)) {
                onionRequest(target, "DELETE", null, ONION_READ_TIMEOUT_MS, isolation = mailboxId).code in 200..204
            } else {
                val url = URL(baseUrl.trimEnd('/') + "/m/" + mailboxId + "?upto=" + seq)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    useCaches = false
                }
                try {
                    conn.responseCode in 200..204
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Potvrzení příjmu selhalo: ${e.javaClass.simpleName}")
            DiagnosticsLog.warn(TAG, "potvrzení příjmu selhalo (${e.javaClass.simpleName})")
            false
        }
        // Číslo `seq` je pořadí v rámci schránky, ne její ID - neprozrazuje nic.
        if (acked) DiagnosticsLog.log(TAG, "příjem potvrzen (seq $seq)")
        return acked
    }

    /**
     * Předehřeje Tor okruh pro danou SOCKS [isolation] (= odesílací schránku).
     * Tor izoluje okruhy podle SOCKS jména ([socksUserPassAuth]), takže levný
     * GET /health poslaný pod stejnou izolací postaví PŘESNĚ ten okruh, který pak
     * použije [put] do téže schránky - první odeslání pak nečeká na studenou stavbu.
     *
     * Cesta požadavku je /health (ne /m/<schránka>), takže se relayi neprozradí ID
     * schránky; izolace je jen lokální SOCKS jméno mezi appkou a jejím Torem.
     * Best-effort: všechny chyby spolkne (je to jen optimalizace). U přímého
     * (ne-onion) spojení se okruhy nestaví, takže se nedělá nic.
     */
    fun prewarm(baseUrl: String, isolation: String) {
        try {
            val target = Target.parse(baseUrl, "/health")
            if (TorManager.isOnion(target.host)) {
                onionRequest(target, "GET", null, ONION_READ_TIMEOUT_MS, isolation = isolation)
            }
        } catch (e: Exception) {
            // Adresu ani izolaci nelogujeme - jen typ chyby.
            Log.w(TAG, "předehřátí okruhu selhalo: ${e.javaClass.simpleName}")
            DiagnosticsLog.log(TAG, "předehřátí okruhu selhalo (${e.javaClass.simpleName})")
        }
    }

    /** Ověří dostupnost relaye (GET /health). */
    fun health(baseUrl: String): Boolean {
        val ok = try {
            val target = Target.parse(baseUrl, "/health")
            if (TorManager.isOnion(target.host)) {
                onionRequest(target, "GET", null, ONION_READ_TIMEOUT_MS, isolation = "health").code == 200
            } else {
                val conn = (URL(baseUrl.trimEnd('/') + "/health").openConnection() as HttpURLConnection)
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.responseCode == 200
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            // Adresu relaye nelogujeme - v release buildu (bez R8) by zůstala
            // v logcatu a prozradila, na jaký server se uživatel připojuje.
            Log.w(TAG, "health selhal: ${e.javaClass.simpleName}")
            DiagnosticsLog.warn(TAG, "relay nedostupný (${e.javaClass.simpleName})")
            false
        }
        if (ok) DiagnosticsLog.log(TAG, "relay dostupný (health ok)")
        return ok
    }

    /**
     * Pošle `.onion` požadavek přes zabudovaný Tor. Nejdřív počká, až Tor otevře
     * SOCKS listener (bootstrap může chvíli trvat) - teprve pak jde přes SOCKS5
     * tunel na skutečný port zabudovaného Toru. Když Tor včas nenaběhne, vyhodí
     * srozumitelnou chybu (místo matoucího „connection refused" na starý port).
     */
    private fun onionRequest(
        target: Target,
        method: String,
        body: ByteArray?,
        readTimeoutMs: Int,
        attempts: Int = ONION_ATTEMPTS,
        contentType: String = OCTET_CONTENT_TYPE,
        isolation: String = "default"
    ): Response {
        if (!TorManager.awaitReady(TOR_READY_TIMEOUT_MS)) {
            Log.w(TAG, "onion $method: Tor není včas připravený")
            DiagnosticsLog.error(TAG, "Tor nenaběhl včas, $method přes onion se neposílá")
            throw IOException("Tor se nespustil nebo nenabootoval včas - zkus to za chvíli znovu")
        }
        val proxy = TorManager.socksHost to TorManager.socksPort
        // Onion okruh (zvlášť první connect na službu) může být zpočátku pomalý
        // nebo selhat, než Tor stáhne deskriptor služby - víc kratších pokusů se
        // „chytne" hned, jakmile je okruh hotový (líp než jeden dlouhý timeout).
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val t0 = System.currentTimeMillis()
                // Prvnímu pokusu dáme delší čas na navázání: studený Tor musí
                // stáhnout deskriptor onion služby a domluvit rendezvous, což
                // trvá déle než běžný connect. Krátký timeout tady znamenal, že
                // se první pokus utnul těsně před cílem a celé připojení se
                // protáhlo na minuty (5 × 18 s marných pokusů).
                val connectTimeout =
                    if (attempt == 0) ONION_FIRST_CONNECT_TIMEOUT_MS else ONION_CONNECT_TIMEOUT_MS
                val r = socksRequest(
                    proxy, target, method, body, connectTimeout, readTimeoutMs, contentType, isolation
                )
                val elapsed = System.currentTimeMillis() - t0
                Log.i(TAG, "onion $method -> HTTP ${r.code} (${elapsed} ms, pokus ${attempt + 1})")
                DiagnosticsLog.log(TAG, "onion $method -> HTTP ${r.code} ($elapsed ms, pokus ${attempt + 1})")
                RelayTelemetry.recordSuccess(elapsed)
                return r
            } catch (e: AfterSendException) {
                // Neopakuj jen u PUT: zápis u serveru možná prošel a opakování by
                // zprávu zdvojilo. GET je díky režimu s potvrzením nedestruktivní
                // (server maže až na `ack`), takže ten se opakovat MUSÍ - jinak by
                // rozpadlé spojení uprostřed odpovědi znamenalo ztracené zprávy.
                //
                // Totéž platí pro POST (hlášení chyby): opakování by hlášení
                // na serveru založilo podruhé.
                if (method == "PUT" || method == "POST") {
                    Log.w(TAG, "onion $method selhal až po odeslání, neopakuji: " +
                        "${e.cause?.javaClass?.simpleName}")
                    DiagnosticsLog.warn(TAG, "onion $method selhal po odeslání, neopakuji " +
                        "(${e.cause?.javaClass?.simpleName})")
                    RelayTelemetry.recordFailure(e.cause?.javaClass?.simpleName ?: "AfterSend")
                    throw e
                }
                last = e
                Log.w(TAG, "onion $method selhal po odeslání (pokus ${attempt + 1}/$attempts): " +
                    "${e.cause?.javaClass?.simpleName}")
                DiagnosticsLog.warn(TAG, "onion $method selhal po odeslání " +
                    "(pokus ${attempt + 1}/$attempts, ${e.cause?.javaClass?.simpleName})")
                if (attempt < attempts - 1) {
                    try { Thread.sleep(ONION_RETRY_DELAY_MS) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw ie
                    }
                }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "onion $method selhal (pokus ${attempt + 1}/$attempts): " +
                    "${e.javaClass.simpleName}")
                // Text výjimky může obsahovat cílovou adresu - proto jen typ.
                DiagnosticsLog.warn(TAG, "onion $method selhal " +
                    "(pokus ${attempt + 1}/$attempts, ${e.javaClass.simpleName})")
                if (attempt < attempts - 1) {
                    try { Thread.sleep(ONION_RETRY_DELAY_MS) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw ie
                    }
                }
            }
        }
        val err = last ?: IOException("onion request failed")
        RelayTelemetry.recordFailure(err.javaClass.simpleName)
        throw err
    }

    // --- Přímé spojení (http/https) ---

    private fun directPut(baseUrl: String, mailboxId: String, blob: ByteArray): Boolean {
        val conn = openDirect(baseUrl, mailboxId, "PUT")
        return try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(blob.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(blob) }
            conn.responseCode in 200..204
        } finally {
            conn.disconnect()
        }
    }

    private fun directGet(baseUrl: String, mailboxId: String, query: String = ""): Fetched {
        val conn = openDirect(baseUrl, mailboxId, "GET", query)
        return try {
            when (val code = conn.responseCode) {
                204 -> Fetched(emptyList(), -1)
                200 -> Fetched(
                    unframe(conn.inputStream.readBytes()),
                    conn.getHeaderField("X-CC-Seq")?.toLongOrNull() ?: -1
                )
                else -> throw IOException("Relay GET vrátil HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openDirect(baseUrl: String, mailboxId: String, method: String, query: String = ""): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/m/" + mailboxId + query)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }
    }

    // --- Spojení přes Tor (SOCKS5 + ruční HTTP/1.1) ---

    /** Cílová adresa rozložená z base URL + cesty. */
    private data class Target(val host: String, val port: Int, val path: String) {
        companion object {
            fun parse(baseUrl: String, resource: String): Target {
                val uri = URI(baseUrl.trim())
                val host = uri.host ?: throw IOException("Neplatná adresa serveru")
                val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
                val prefix = uri.rawPath?.trimEnd('/').orEmpty()
                return Target(host, port, prefix + resource)
            }
        }
    }

    /**
     * Pošle jeden HTTP požadavek přes SOCKS5 proxy (Tor). `.onion` host se předá
     * proxy k rozřešení (remote DNS), takže se nikdy neřeší lokálně. Vrací
     * (stavový kód, tělo).
     */
    private fun socksRequest(
        proxy: Pair<String, Int>,
        target: Target,
        method: String,
        body: ByteArray?,
        connectTimeoutMs: Int = TIMEOUT_MS,
        readTimeoutMs: Int = TIMEOUT_MS,
        contentType: String = OCTET_CONTENT_TYPE,
        isolation: String = "default"
    ): Response {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.first, proxy.second), connectTimeoutMs)
            // Do navázání okruhu (SOCKS handshake vč. čekání na rendezvous) platí
            // connect timeout; po něm se přepne na (delší) čtecí timeout kvůli long-pollu.
            socket.soTimeout = connectTimeoutMs
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 pozdrav. Nabízíme "bez autentizace" i "jméno/heslo" - Tor má
            // ve výchozím stavu zapnuté IsolateSOCKSAuth, takže požadavky s RŮZNÝM
            // jménem posílá PŘES RŮZNÉ OKRUHY.
            //
            // Proč na tom záleží: bez izolace by odchozí PUT (schránka dir 0) a
            // příchozí GET (schránka dir 1) dorazily k onion službě přes tentýž
            // okruh - a relay by si je spojil, tedy zjistil, kdo komu píše. To je
            // přesně to, čemu má celá appka bránit.
            out.write(byteArrayOf(0x05, 0x01, 0x02))
            out.flush()
            val greeting = readExactly(input, 2)
            if (greeting[0].toInt() != 0x05) throw IOException("SOCKS5 handshake selhal")
            // JEN user/pass. Kdybychom povolili i "bez autentizace", izolace by
            // při volbě 0x00 tiše odpadla a relay by spojil odchozí a příchozí
            // schránku - tedy zjistil, kdo komu píše. Radši spojení nenavázat.
            if ((greeting[1].toInt() and 0xFF) != 0x02) {
                throw IOException("SOCKS5: proxy nepodporuje izolaci okruhů")
            }
            socksUserPassAuth(out, input, isolation)

            // CONNECT na doménu (ATYP 0x03) - host řeší proxy (Tor).
            val hostBytes = target.host.toByteArray(Charsets.US_ASCII)
            val connect = ByteArrayOutputStream()
            connect.write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
            connect.write(hostBytes.size)
            connect.write(hostBytes)
            connect.write((target.port ushr 8) and 0xFF)
            connect.write(target.port and 0xFF)
            out.write(connect.toByteArray())
            out.flush()

            // Odpověď: VER REP RSV ATYP BND.ADDR BND.PORT
            val reply = readExactly(input, 4)
            if (reply[1].toInt() != 0x00) {
                throw IOException("SOCKS5 connect selhal (kód ${reply[1].toInt()})")
            }
            val addrLen = when (reply[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> readExactly(input, 1)[0].toInt() and 0xFF
                else -> throw IOException("SOCKS5 neznámý ATYP")
            }
            readExactly(input, addrLen + 2) // přeskoč navázanou adresu a port

            // Okruh na službu je navázaný - teď platí čtecí timeout (u long-pollu delší).
            socket.soTimeout = readTimeoutMs

            // HTTP/1.1 přes tunel. Connection: close -> tělo čteme až do konce.
            val head = StringBuilder()
            head.append("$method ${target.path} HTTP/1.1\r\n")
            head.append("Host: ${target.host}\r\n")
            if (body != null) {
                head.append("Content-Type: $contentType\r\n")
                head.append("Content-Length: ${body.size}\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            if (body != null) out.write(body)
            out.flush()

            // Od téhle chvíle už požadavek u serveru JE. Selhání při čtení
            // odpovědi proto označíme zvlášť - opakovat PUT by znamenalo uložit
            // blob podruhé a příjemci by zpráva dorazila dvakrát.
            return try {
                parseHttpResponse(readCapped(input))
            } catch (e: Exception) {
                throw AfterSendException(e)
            }
        }
    }

    /** Selhání, které nastalo až po odeslání požadavku (viz [socksRequest]). */
    private class AfterSendException(cause: Throwable) : IOException(cause)

    /**
     * Přečte odpověď, ale nejvýš [MAX_RESPONSE_BYTES]. Bez stropu by nepřátelský
     * relay poslal stovky MB a shodil foreground service na `OutOfMemoryError` -
     * tedy trvalý výpadek příjmu zpráv.
     */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_RESPONSE_BYTES) {
                throw IOException("Odpověď relaye je přes limit ($MAX_RESPONSE_BYTES B)")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * SOCKS5 autentizace jménem/heslem (RFC 1929). Tor obsah neověřuje - používá
     * ho jen jako klíč izolace okruhů, takže heslo může být cokoli.
     */
    private fun socksUserPassAuth(out: java.io.OutputStream, input: InputStream, isolation: String) {
        val user = isolation.take(255).toByteArray(Charsets.US_ASCII)
        val pass = byteArrayOf(0x78) // "x" - Tor heslo neřeší
        val auth = ByteArrayOutputStream()
        auth.write(0x01)
        auth.write(user.size)
        auth.write(user)
        auth.write(pass.size)
        auth.write(pass)
        out.write(auth.toByteArray())
        out.flush()
        val reply = readExactly(input, 2)
        if (reply[1].toInt() != 0x00) throw IOException("SOCKS5 autentizace selhala")
    }

    /** Přečte přesně [n] bajtů, nebo vyhodí výjimku při předčasném konci. */
    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw IOException("Předčasný konec streamu")
            read += r
        }
        return buf
    }

    /** HTTP odpověď rozložená na kód, hlavičky a tělo. */
    private data class Response(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        /** Pořadové číslo pro potvrzení příjmu (`X-CC-Seq`), nebo -1. */
        fun seq(): Long = headers["x-cc-seq"]?.toLongOrNull() ?: -1
    }

    /** Rozdělí HTTP odpověď na kód, hlavičky a tělo (dělí je prázdný řádek). */
    private fun parseHttpResponse(data: ByteArray): Response {
        val sep = indexOfHeaderEnd(data)
        if (sep < 0) throw IOException("Neplatná HTTP odpověď")
        val header = String(data, 0, sep, Charsets.US_ASCII)
        val lines = header.split("\r\n")
        val code = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Neplatný stavový řádek: ${lines.firstOrNull()}")
        // Názvy hlaviček na malá písmena, ať na velikosti nezáleží.
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null
            else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val body = data.copyOfRange(sep + 4, data.size)
        // Useknuté tělo (rozpadlý okruh) vypadá jako čistý konec streamu. Bez téhle
        // kontroly by klient zpracoval jen část blobů, ale potvrdil celou dávku -
        // a server by smazal i to, co nikdy nedorazilo.
        val declared = headers["content-length"]?.toIntOrNull()
        if (declared != null && body.size != declared) {
            throw IOException("Neúplná odpověď (${body.size} z $declared B)")
        }
        return Response(code, headers, body)
    }

    /** Najde index prázdného řádku (\r\n\r\n) oddělujícího hlavičky od těla. */
    private fun indexOfHeaderEnd(data: ByteArray): Int {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 13 && data[i + 1].toInt() == 10 &&
                data[i + 2].toInt() == 13 && data[i + 3].toInt() == 10
            ) return i
            i++
        }
        return -1
    }

    /** Rozbalí délkově rámované bloby: [4B big-endian délka][data]... */
    private fun unframe(data: ByteArray): List<ByteArray> = unframeBlobs(data)
}

/**
 * Rozbalí délkově rámované bloby: `[4B big-endian délka][data]…`. Top-level a
 * `internal`, aby šla otestovat bez sítě.
 *
 * Klíčový guard `len < 0 || len > zbytek` chrání před přetečením: u délky blízko
 * 2^31 by `i + len` přeteklo, podmínka `i + len <= size` by prošla a `copyOfRange`
 * by vyhodil výjimku - schránka by se stala TRVALE nečitelnou. Proto se porovnává
 * odečítáním (`len > data.size - i`), ne přičítáním. Neúplný poslední rámec
 * (kratší než hlásí délka) se zahodí - vrátí se jen celé bloby.
 */
internal fun unframeBlobs(data: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var i = 0
    while (i + 4 <= data.size) {
        val len = ((data[i].toInt() and 0xFF) shl 24) or
            ((data[i + 1].toInt() and 0xFF) shl 16) or
            ((data[i + 2].toInt() and 0xFF) shl 8) or
            (data[i + 3].toInt() and 0xFF)
        i += 4
        if (len < 0 || len > data.size - i) break
        out.add(data.copyOfRange(i, i + len))
        i += len
    }
    return out
}
