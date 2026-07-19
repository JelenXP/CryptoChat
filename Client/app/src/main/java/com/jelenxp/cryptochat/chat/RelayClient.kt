package com.jelenxp.cryptochat.chat

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

    private const val TIMEOUT_MS = 30_000

    /** Uloží blob do schránky. Vrací true při úspěchu (2xx). */
    fun put(baseUrl: String, mailboxId: String, blob: ByteArray): Boolean {
        val target = Target.parse(baseUrl, "/m/$mailboxId")
        val proxy = TorManager.socksFor(target.host)
        return if (proxy != null) {
            val (code, _) = socksRequest(proxy, target, "PUT", blob)
            code in 200..204
        } else {
            directPut(baseUrl, mailboxId, blob)
        }
    }

    /** Vyzvedne (a na serveru smaže) všechny čekající bloby. Prázdná schránka = prázdný seznam. */
    fun get(baseUrl: String, mailboxId: String): List<ByteArray> {
        val target = Target.parse(baseUrl, "/m/$mailboxId")
        val proxy = TorManager.socksFor(target.host)
        return if (proxy != null) {
            val (code, body) = socksRequest(proxy, target, "GET", null)
            when (code) {
                204 -> emptyList()
                200 -> unframe(body)
                else -> throw IOException("Relay GET vrátil HTTP $code")
            }
        } else {
            directGet(baseUrl, mailboxId)
        }
    }

    /** Ověří dostupnost relaye (GET /health). */
    fun health(baseUrl: String): Boolean {
        return try {
            val target = Target.parse(baseUrl, "/health")
            val proxy = TorManager.socksFor(target.host)
            if (proxy != null) {
                socksRequest(proxy, target, "GET", null).first == 200
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
            false
        }
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

    private fun directGet(baseUrl: String, mailboxId: String): List<ByteArray> {
        val conn = openDirect(baseUrl, mailboxId, "GET")
        return try {
            when (val code = conn.responseCode) {
                204 -> emptyList()
                200 -> unframe(conn.inputStream.readBytes())
                else -> throw IOException("Relay GET vrátil HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openDirect(baseUrl: String, mailboxId: String, method: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/m/" + mailboxId)
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
        body: ByteArray?
    ): Pair<Int, ByteArray> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxy.first, proxy.second), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 pozdrav: verze 5, 1 metoda, bez autentizace.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = readExactly(input, 2)
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                throw IOException("SOCKS5 handshake selhal")
            }

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

            // HTTP/1.1 přes tunel. Connection: close -> tělo čteme až do konce.
            val head = StringBuilder()
            head.append("$method ${target.path} HTTP/1.1\r\n")
            head.append("Host: ${target.host}\r\n")
            if (body != null) {
                head.append("Content-Type: application/octet-stream\r\n")
                head.append("Content-Length: ${body.size}\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            if (body != null) out.write(body)
            out.flush()

            val response = input.readBytes()
            return parseHttpResponse(response)
        }
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

    /** Rozdělí HTTP odpověď na stavový kód a tělo (hlavičky/tělo dělí prázdný řádek). */
    private fun parseHttpResponse(data: ByteArray): Pair<Int, ByteArray> {
        val sep = indexOfHeaderEnd(data)
        if (sep < 0) throw IOException("Neplatná HTTP odpověď")
        val header = String(data, 0, sep, Charsets.US_ASCII)
        val statusLine = header.substringBefore("\r\n")
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Neplatný stavový řádek: $statusLine")
        val body = data.copyOfRange(sep + 4, data.size)
        return code to body
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
    private fun unframe(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            i += 4
            if (len < 0 || i + len > data.size) break
            out.add(data.copyOfRange(i, i + len))
            i += len
        }
        return out
    }
}
