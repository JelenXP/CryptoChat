package com.jelenxp.cryptochat.chat

import android.content.Context
import android.util.Log
import com.jelenxp.cryptochat.diagnostics.DiagnosticsLog
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
 * požadavky (a čeká přes `awaitReady`, dokud Tor listener neotevře).
 *
 * Tor běží jako proces vázaný na proces appky (bez foreground služby s notifikací).
 * Pro chat, který dotazuje server jen když je appka aktivní, to stačí; keep-alive
 * na pozadí by teprve vyžadoval `runtime-service-ui`.
 *
 * Spouští se jednou (idempotentně) - typicky když je nastavený `.onion` relay.
 * Průběh (start démona, SOCKS port, chyby Toru) se loguje pod tagem [TAG], ať
 * jde bootstrap diagnostikovat i v release buildu (`adb logcat -s TorController`).
 */
object TorController {

    private const val TAG = "TorController"

    /** Z hlášky Toru „Bootstrapped 45% (…)" vytáhne jen procento. */
    private val BOOTSTRAP_RE = Regex("Bootstrapped (\\d+)%")

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
                    Log.i(TAG, "SOCKS listener otevřen: ${socks.address.value}:${socks.port.value}")
                    // Port je lokální (127.0.0.1) a náhodně přidělený - neprozrazuje nic.
                    DiagnosticsLog.log(TAG, "Tor připraven, SOCKS listener otevřen")
                    TorManager.configure(socks.address.value, socks.port.value, ready = true)
                } else {
                    Log.w(TAG, "SOCKS listener zavřen / žádný")
                    DiagnosticsLog.warn(TAG, "SOCKS listener zavřen - Tor není k dispozici")
                    // Zahoď i runtime. Bez toho by `ensureStarted` (vrací se hned,
                    // když runtime != null) bylo po pádu démona napořád no-op a
                    // chat by byl mrtvý až do restartu procesu - každý požadavek
                    // by jen marně čekal na timeout.
                    forgetRuntime()
                    TorManager.resetBootstrap()
                    TorManager.markStopped()
                }
            }
            // Logy Toru (NOTICE ukazuje bootstrap %, WARN/ERR případné problémy).
            //
            // Do diagnostiky jde z NOTICE JEN postup bootstrapu - celé řádky by
            // mohly obsahovat cílové adresy. U WARN/ERR se navíc `.onion` adresy
            // maskují (DiagnosticsLog.redact) a text se krátí.
            observerStatic(TorEvent.NOTICE, OnEvent.Executor.Immediate) { line ->
                // Syrový řádek NELOGOVAT - obsahuje cílovou .onion adresu.
                // R8 je vypnutý, takže v release buildu by zůstal v logcatu.
                BOOTSTRAP_RE.find(line)?.let { match ->
                    val percent = match.groupValues[1]
                    DiagnosticsLog.log(TAG, "bootstrap Toru $percent %")
                    // Teprve po 100 % má Tor postavené okruhy a má smysl posílat
                    // požadavky na onion službu (viz TorManager.awaitReady).
                    if (percent == "100") TorManager.markBootstrapped()
                }
            }
            observerStatic(TorEvent.WARN, OnEvent.Executor.Immediate) { line ->
                Log.w(TAG, "tor WARN: ${DiagnosticsLog.redact(line).take(160)}")
                DiagnosticsLog.warn(TAG, "tor WARN: ${DiagnosticsLog.redact(line).take(160)}")
            }
            observerStatic(TorEvent.ERR, OnEvent.Executor.Immediate) { line ->
                Log.e(TAG, "tor ERR: ${DiagnosticsLog.redact(line).take(160)}")
                DiagnosticsLog.error(TAG, "tor ERR: ${DiagnosticsLog.redact(line).take(160)}")
            }
            required(TorEvent.NOTICE)
            required(TorEvent.ERR)
            required(TorEvent.WARN)
        }
        runtime = rt

        Log.i(TAG, "Startuji zabudovaný Tor (StartDaemon)…")
        DiagnosticsLog.log(TAG, "startuji zabudovaný Tor")
        // Nastartuj démona. enqueue je neblokující - bootstrap Toru (desítky
        // sekund) běží na pozadí, výsledek se projeví přes LISTENERS observer výše.
        rt.enqueue(
            action = Action.StartDaemon,
            onFailure = OnFailure { t ->
                Log.e(TAG, "Tor StartDaemon selhal", t)
                DiagnosticsLog.error(TAG, "start Toru selhal (${t.javaClass.simpleName})")
                // Runtime MUSÍME zahodit, jinak by `ensureStarted` (které se
                // vrací hned, když `runtime != null`) bylo napořád no-op a Tor
                // by se do restartu procesu už nikdy nezkusil nastartovat.
                forgetRuntime()
                TorManager.resetBootstrap()
                TorManager.markStopped()
            },
            onSuccess = OnSuccess.noOp(),
        )
    }

    /** Zapomene neúspěšný runtime, aby šel start zkusit znovu. */
    @Synchronized
    private fun forgetRuntime() {
        runtime = null
    }
}
