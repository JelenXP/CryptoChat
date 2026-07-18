package com.example.cryptochat

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Neošetřenou výjimku sice nejde "vyléčit" a appka i tak spadne (to je
 * v Androidu normální a záměrné chování), ale díky tomuto handleru se
 * poslední pád zapíše do souboru `crash_log.txt` v interním úložišti
 * aplikace. Jde ho pak vyčíst přes adb (`adb shell run-as
 * com.example.cryptochat cat files/crash_log.txt`) nebo souborovým
 * manažerem s root přístupem - užitečné pro diagnostiku i bez nutnosti mít
 * telefon připojený k počítači v okamžiku pádu.
 *
 * Skutečná ochrana proti pádům je v jednotlivých try/catch blocích v kódu
 * (ContactRepository, KeystoreCryptoHelper, AppLock) - tenhle handler je
 * jen "poslední záchranná síť" pro diagnostiku toho, co se případně nechytilo.
 */
class CryptoChatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val logFile = File(filesDir, "crash_log.txt")
                logFile.writeText("[$timestamp] Uncaught exception on thread ${thread.name}:\n$sw")
                Log.e("CryptoChatApp", "Uncaught exception, wrote crash_log.txt", throwable)
            } catch (loggingError: Exception) {
                // I logování pádu může teoreticky selhat (např. plný disk) -
                // v tom případě prostě pokračujeme na výchozí handler beze
                // změny, ať appka spadne standardním způsobem.
                Log.e("CryptoChatApp", "Failed to write crash log", loggingError)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
