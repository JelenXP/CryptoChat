# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Komunikace a jazyk

- S uživatelem komunikuj **česky**.
- Kód piš **anglicky** (názvy tříd, funkcí, proměnných, řetězcové konstanty apod.).
- Komentáře a docstringy piš **česky**.

## Projekt

CryptoChat je jednomodulová Android aplikace (Kotlin + Jetpack Compose), která demonstruje end-to-end šifrování textových zpráv mezi dvěma lidmi. **Nemá vůbec žádnou síťovou vrstvu** — zašifrované zprávy vznikají jako Base64 řetězce, které uživatel ručně zkopíruje a pošle libovolným kanálem (SMS, e-mail…) a na druhé straně je vloží zpět k dešifrování. Všechny kontakty a klíče leží pouze na zařízení v SharedPreferences.

Pozn.: README.md i všechny komentáře/docstringy v kódu jsou v **češtině**. Texty UI jsou v `res/values/strings.xml` (angličtina, výchozí fallback) a `res/values-cs/strings.xml` (čeština).

## Build a spuštění

Tento stroj **umí projekt lokálně sestavit** — Android SDK (`C:\Users\jelen\AppData\Local\Android\Sdk`, cesta je v `local.properties`) i JDK 17 (Temurin) jsou nainstalované a Gradle wrapper (`gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar`) je v repu.

- **Sestavení APK z terminálu (Windows PowerShell)**: `.\gradlew.bat assembleDebug`. Výstup: `app/build/outputs/apk/debug/app-debug.apk`. Analogicky `.\gradlew.bat lint` a `.\gradlew.bat test` (testy zatím žádné neexistují). Na Linux/macOS je ekvivalent `./gradlew …`.
- **Android Studio**: File → Open kořen projektu, nechat proběhnout Gradle sync, pak Build → Build APK(s). Stejný výstup.
- **CI**: `.github/workflows/build-apk.yml` spouští `gradle assembleDebug` při push do `main`/`master` (nebo ručně přes `workflow_dispatch`), používá JDK 17 + Gradle 8.7 a nahraje APK jako artefakt `CryptoChat-debug-apk`.

**Automatický build po každé změně:** Po dokončení jakékoli úpravy kódu, resources nebo konfigurace (tj. čehokoli, co může ovlivnit APK) vždy automaticky spusť `.\gradlew.bat assembleDebug`, ověř, že build skončil `BUILD SUCCESSFUL`, a nahlas výsledek i cestu k APK. Pokud build selže, oprav příčinu — nekonči s rozbitým buildem. Ryze textové/dokumentační změny (např. tento soubor, README) build nevyžadují.

**Unit testy:** `.\gradlew.bat testDebugUnitTest` — běží na čistém JVM (bez emulátoru), protože krypto používá `java.util.Base64` (ne `android.util.Base64`). Testy jsou v `app/src/test/java/.../crypto/` (`CryptoManager`, `PostQuantumKem`, `Base64Util`). CI je pouští před buildem APK.

**Release build:** `release` má zapnutý **R8** (`isMinifyEnabled` + `isShrinkResources`, pravidla v `app/proguard-rules.pro` — BouncyCastle se ponechává celý). Zmenší APK z ~20 MB na ~5 MB. `.\gradlew.bat assembleRelease` (bez podpisu → `app-release-unsigned.apk`).

**Offline prostředí:** Tento stroj nemá přístup k Maven/Google repozitářům (SSL). Cachované závislosti stačí na `assembleDebug`/`assembleRelease`, ale úlohy stahující nové artefakty tady selžou: `lint`/`lintVitalRelease` (proto u `assembleRelease` přidej `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease`) a `testDebugUnitTest` (junit/hamcrest nejsou v cache). Oboje projde v CI, kde je síť.

Toolchain: AGP 8.5.2, Kotlin 1.9.24, Compose Compiler 1.5.14 (Compose BOM 2024.06.00), `compileSdk`/`targetSdk` 34, `minSdk` 26, JVM target 1.8. Gradle 8.7.

## Architektura

Jediná Activity (`MainActivity`) hostuje Compose `NavHost`. Žádný DI framework, žádný Room, žádná rozhraní repozitářů — prosté `object` singletony a jeden `AndroidViewModel`.

**Vrstvy**

- `crypto/` — veškerá kryptografie jako bezstavové `object` singletony:
  - `CryptoManager` — šifrování zpráv. AES-256-GCM; formát payloadu je `Base64(IV[12] || ciphertext || GCM tag[16])`. Dešifrování při poškození/špatném klíči záměrně vyhodí výjimku (GCM kontrola integrity) — neopravovat tím, že se výjimka spolkne.
  - `KeystoreCryptoHelper` — šifrování **at rest**. Neexportovatelný AES klíč v Android Keystore zabalí sdílený klíč každého kontaktu předtím, než jde do SharedPreferences. `decryptFromStorage` vrací `null` (nikdy nevyhodí výjimku), pokud byl Keystore klíč zneplatněn.
  - `PostQuantumKem` — vzdálená výměna klíče bez serveru přes **ML-KEM-768** (FIPS 203) pomocí BouncyCastle (`org.bouncycastle:bcprov-jdk18on`). Ze surového sdíleného tajemství odvodí přes **HKDF-SHA256 s doménovou separací** (dva různé `info` labely) dvě nezávislé hodnoty: 32bajtový AES-256-GCM klíč pro `CryptoManager` a krátký ověřovací kód SAS. SAS se čte nahlas (veřejný) jako obrana proti MITM — díky HKDF jeho prozrazení neřekne nic o šifrovacím klíči. `encapsulate`/`decapsulate` vracejí `SharedKeys(aesKeyBase64, verificationCode)`; `VerificationCodeContent` dostává hotový kód. Pozn.: změna `info` labelů nebo délek změní odvozené klíče — obě strany musí běžet na stejné verzi (netýká se už uložených kontaktů ani osobní výměny).
- `data/` — `Contact` (id/name/nullable `keyBase64`), `ContactRepository` (JSON v SharedPreferences), `SettingsRepository` (zámek appky + jazyk).
- `viewmodel/ContactsViewModel` — jediný `AndroidViewModel`, vystavuje `contacts` jako `StateFlow`; každá změna volá `refresh()` a načte znovu z repozitáře.
- `ui/screens/` — jedna composable na každý navigační cíl; `ui/lock/AppLock.kt` (biometrický zámek), `ui/qr/` (generování QR přes ZXing + `buildQrScanOptions`/`PortraitCaptureActivity` pro sken zamčený na výšku + `QrCard`).
- `ui/theme/` — Material 3 design systém: značková tyrkysová paleta (`Color.kt`), světlý/tmavý `ColorScheme` a `CryptoChatTheme` (`Theme.kt`, bez Material You dynamických barev), typografie (`Type.kt`). `MainActivity` obaluje obsah do `CryptoChatTheme`.
- `ui/components/CommonUi.kt` — sdílené prvky napříč obrazovkami: `CryptoScaffold` (top bar + tlačítko zpět), `InfoCard` (instrukční/varovné karty), `CopyableField` (read-only pole s kopírováním). `ui/util/Clipboard.kt` má jednotné `copyToClipboard`. Nové obrazovky používej přes tyhle prvky, ať zůstane vzhled konzistentní a bez duplicity.

**Dva způsoby výměny klíče** (oba končí sdíleným 32bajtovým AES klíčem uloženým u kontaktu):
1. *Osobně* — `CreateKeyScreen` (vygenerovat + zobrazit klíč jako text/QR) ↔ `AcceptKeyScreen` (naskenovat/vložit). Důvěra plyne z fyzického kanálu.
2. *Na dálku* — `RemoteInitScreen` (`generateKeyPair`, poslat veřejný klíč) ↔ `RemoteCompleteScreen` (`encapsulate`, odpovědět) → zpět na init (`decapsulate`). Obě strany pak potvrdí SAS kód přes `VerificationCodeContent`, teprve pak se kontakt uloží.

## Konvence a invarianty

- **Odolnost proti pádům je záměrný cíl návrhu.** `ContactRepository` obaluje každou operaci do try/catch a vrací `Boolean`/prázdné seznamy místo vyhazování výjimek; jeden poškozený uložený záznam se přeskočí, není fatální. `CryptoChatApplication` instaluje globální handler neošetřených výjimek, který zapisuje do `files/crash_log.txt`. Při úpravách těchto míst tento přístup zachovej — nezaváděj neošetřené výjimky do cest ukládání/startu.
- **`MainActivity` dědí z `AppCompatActivity`** (ne z `ComponentActivity`) právě proto, aby fungovalo `AppCompatDelegate.setApplicationLocales()` pro přepínání jazyka uvnitř appky. To vyžaduje motiv `Theme.CryptoChat` (odvozený z `Theme.AppCompat.DayNight.NoActionBar`) — obyčejný systémový motiv spadne hned při startu s „You need to use a Theme.AppCompat theme".
- **`android:allowBackup="false"`** je záměrné (drží klíče mimo cloudové zálohy) — ponechat.
- Navigační trasy předávají argument `name` URL-enkódovaný; při čtení zpět dekóduj přes `Uri.decode` (viz `MainActivity`).
- BouncyCastle ML-KEM API (`org.bouncycastle.pqc.crypto.mlkem.*`) se v tomto prostředí **úspěšně kompiluje** (ověřeno `assembleDebug`). Pokud přesto build spadne v `PostQuantumKem.kt` po změně verze `bcprov-jdk18on`, podezřívej odlišné názvy tříd/metod oproti použité verzi.
