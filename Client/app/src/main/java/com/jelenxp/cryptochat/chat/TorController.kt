package com.jelenxp.cryptochat.chat

import android.content.Context
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption

/**
 * Spouští zabudovaný Tor (kmp-tor), aby appka došla na `.onion` relay bez toho,
 * aby si uživatel musel instalovat Orbot. Po nabootování Toru zjistí jeho SOCKS
 * port a předá ho [TorManager] - přes něj pak [RelayClient] posílá `.onion`
 * požadavky.
 *
 * Tor běží jako proces vázaný na proces appky (bez foreground služby s notifikací).
 * Pro chat, který dotazuje server jen když je appka aktivní, to stačí; keep-alive
 * na pozadí by teprve vyžadoval `runtime-service-ui`.
 *
 * Spouští se jednou (idempotentně) - typicky když je nastavený `.onion` relay.
 */
object TorController {

    @Volatile
    private var runtime: TorRuntime? = null

    @Synchronized
    fun ensureStarted(context: Context) {
        if (runtime != null) return
        val app = context.applicationContext

        // Pracovní a cache adresáře Toru v soukromém úložišti appky.
        val workDir = app.getDir("kmptor", Context.MODE_PRIVATE).absolutePath.toFile()
        val cacheDir = java.io.File(app.cacheDir, "kmptor").absolutePath.toFile()

        val environment = TorRuntime.Environment.Builder(
            workDirectory = workDir,
            cacheDirectory = cacheDir,
            loader = ResourceLoaderTorExec::getOrCreate,
        )

        val rt = TorRuntime.Builder(environment) {
            // Automaticky přidělený SOCKS port (na 127.0.0.1).
            config {
                TorOption.__SocksPort.configure { auto() }
            }
            // Jakmile Tor otevře SOCKS listener, předáme jeho adresu do fasády,
            // přes kterou RelayClient posílá .onion požadavky.
            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { listeners ->
                val socks = listeners.socks.firstOrNull()
                if (socks != null) {
                    TorManager.configure(socks.address.value, socks.port.value, ready = true)
                } else {
                    TorManager.ready = false
                }
            }
            required(TorEvent.ERR)
            required(TorEvent.WARN)
        }
        runtime = rt

        // Nastartuj démona. enqueue je neblokující - bootstrap Toru (desítky
        // sekund) běží na pozadí, výsledek se projeví přes LISTENERS observer výše.
        rt.enqueue(
            action = Action.StartDaemon,
            onFailure = OnFailure {},
            onSuccess = OnSuccess.noOp(),
        )
    }
}
