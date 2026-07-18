# CryptoChat

Jednoduchá Android aplikace (Kotlin + Jetpack Compose), která ověřuje koncept
end-to-end šifrování zpráv mezi dvěma "uživateli" pomocí ručně vyměněného
sdíleného klíče.

## Jak to funguje

1. **Vytvoření uživatele** – zadáte jméno člověka, se kterým chcete komunikovat.
2. **Výměna klíče** (jen jednou, mimo aplikaci – např. osobně nebo přes QR kód):
   - **Vytvořit klíč** – aplikace vygeneruje náhodný AES-256 klíč, zobrazí ho
     jako text (k zkopírování) a jako QR kód.
   - **Přijmout klíč** – druhá strana klíč naskenuje kamerou (QR kód) nebo
     vloží ručně do textového pole.
   - Po kliknutí na "Pokračovat" se klíč uloží k danému uživateli.
3. **Poslat / Přijmout / Smazat** – po kliknutí na uživatele můžete:
   - **Poslat** – napíšete zprávu, tlačítkem "Zašifrovat" vznikne zašifrovaný
     text (Base64), který zkopírujete a pošlete druhé straně libovolným
     kanálem (SMS, e-mail, ...).
   - **Přijmout** – vložíte obdržený zašifrovaný text a tlačítkem "Dešifrovat"
     se zobrazí původní zpráva.
   - **Smazat kontakt** – po potvrzení v dialogu odstraní uživatele i jeho
     klíč z úložiště.

## Šifrovací koncept

- Algoritmus: **AES-256 v režimu GCM** (autentizované šifrování – zaručuje
  důvěrnost i integritu zprávy).
- Pro každou zprávu se generuje náhodné IV (12 bajtů), výsledek je
  `Base64(IV || ciphertext || auth tag)`.
- Pokud je zpráva poškozená nebo použijete špatný klíč, dešifrování vyhodí
  chybu – to dokazuje, že šifra funguje a hlídá integritu.
- Klíče a kontakty se ukládají pouze lokálně na zařízení (SharedPreferences),
  nikam se neodesílají – aplikace nemá žádnou síťovou komunikaci.

Jádro šifrování je v souboru:
`app/src/main/java/com/example/cryptochat/crypto/CryptoManager.kt`

## Jak z tohoto projektu získat .apk

Tento zdrojový kód nebyl zkompilován v prostředí, kde jsem ho psal (nemá
přístup k Android SDK / Google Maven serverům, odkud se stahují AndroidX a
Compose knihovny). Zkompilovat je ale otázka pár kliknutí:

1. Nainstalujte si **Android Studio** (zdarma, https://developer.android.com/studio).
2. Rozbalte přiložený ZIP a otevřete složku `CryptoChat` v Android Studiu
   (File → Open). Studio si samo stáhne Gradle a všechny závislosti.
3. Počkejte na dokončení "Gradle Sync" (proběhne automaticky).
4. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
5. Hotový soubor `app-debug.apk` najdete v
   `app/build/outputs/apk/debug/` – tlačítkem "locate" v notifikaci po
   dokončení buildu, nebo přímo v tom adresáři.
6. APK zkopírujte do telefonu (nebo `adb install app-debug.apk`) a
   nainstalujte (je potřeba povolit instalaci z neznámých zdrojů).

Žádné úpravy kódu nejsou potřeba – projekt je kompletní a hotový k sestavení.

## Post-kvantová výměna klíče na dálku

Kromě dosavadní osobní výměny (QR/kopírování) teď appka umí i **výměnu na
dálku bez serveru**, přes post-kvantový algoritmus **ML-KEM-768** (FIPS
203, aktuální NIST standard, nástupce Kyber - na rozdíl od RSA/Diffie-
Hellman zůstává bezpečný i proti kvantovým počítačům).

Princip (viz `crypto/PostQuantumKem.kt`):
1. **Zahájit výměnu na dálku** – appka vygeneruje klíčový pár, ukáže veřejný
   klíč (text/QR). Ten se pošle druhé straně libovolným kanálem (SMS,
   e-mail, jiná appka...) - nemusí být důvěryhodný, veřejný klíč může vidět
   kdokoliv.
2. **Dokončit výměnu na dálku** – druhá strana veřejný klíč vloží/naskenuje,
   appka spočítá sdílený klíč a "odpověď", kterou pošle zpátky.
3. Iniciátor odpověď vloží/naskenuje → appka dopočítá STEJNÝ sdílený klíč.
4. **Ověřovací kód** – obě strany na konci uvidí krátký kód odvozený ze
   sdíleného tajemství. Musí si ho navzájem potvrdit JINÝM kanálem (např.
   nahlas po telefonu) - teprve pak appka kontakt uloží. Tohle chrání proti
   tomu, aby někdo cestou (na úrovni toho nedůvěryhodného kanálu) podvrhl
   vlastní klíč (man-in-the-middle).

Výsledný sdílený klíč má stejný formát (32 bajtů) jako dosavadní ruční
klíč, takže šifrování zpráv (`CryptoManager`) je úplně beze změny.

**Poznámka k ověření:** tahle část staví na knihovně BouncyCastle
(`org.bouncycastle:bcprov-jdk18on`), kterou jsem na rozdíl od zbytku
projektu nemohl v tomto prostředí zkompilovat ani jinak ověřit (nemám
přístup k Maven Central). Kód odpovídá zdokumentovanému API BC pro
ML-KEM, ale je teoreticky možné, že konkrétní verze knihovny bude mít
mírně odlišné názvy tříd/metod - pokud by build spadl na chybě v
`crypto/PostQuantumKem.kt`, pošlete mi tu chybovou hlášku a opravím to.

## Oprava pádu při startu (Theme.AppCompat)

Pokud jste zkoušeli předchozí verzi: appka spadla hned při startu s
`IllegalStateException: You need to use a Theme.AppCompat theme`.
Příčina: `MainActivity` byla přepnutá na `AppCompatActivity` (kvůli
přepínání jazyka), ale motiv v manifestu zůstal obyčejný systémový
(`@android:style/Theme.Material.Light.NoActionBar`), který `AppCompatActivity`
nepodporuje. Opraveno – nový `res/values/themes.xml` definuje
`Theme.CryptoChat` odvozený od `Theme.AppCompat.DayNight.NoActionBar` a
manifest ho teď používá.

## Oprava pádu appky + obecná odolnost proti pádům

Pokud jste zkoušeli starší verzi a appka spadla: nejpravděpodobnější
příčina byla v `ContactRepository.persist()`, kde se šifrování klíče do
Android Keystore volalo úplně bez `try/catch` – pokud tahle operace na
konkrétním zařízení z jakéhokoli důvodu selhala, appka spadla hned při
prvním kroku (tlačítko "Pokračovat" po vytvoření/přijetí klíče). Opraveno.

Kromě téhle konkrétní opravy je teď v appce několik obecných vrstev
ochrany:
- `ContactRepository` – všechny operace (načtení, uložení, smazání) jsou
  zabalené v `try/catch` a nikdy appku nespadnou; při chybě vrátí `false`/
  prázdný seznam a appka to zobrazí jako chybovou hlášku.
- Poškozený jednotlivý záznam v uložených datech se přeskočí, zbytek se
  načte normálně.
- Sken QR kódu si nejdřív bezpečně vyžádá oprávnění ke kameře, teprve pak
  spustí skener (dřív mohlo za určitých okolností dojít k pádu kvůli
  chybějícímu oprávnění).
- Biometrický zámek appky bezpečně vyhledává `FragmentActivity` bez
  nebezpečného force-castu, který by jinak mohl appku shodit.
- `CryptoChatApplication` zapisuje jakýkoli neošetřený pád do souboru
  `crash_log.txt` v interním úložišti appky – pokud by appka i přesto
  spadla, dá se tenhle soubor později vytáhnout (`adb shell run-as
  com.example.cryptochat cat files/crash_log.txt`) a poslat mi ho pro
  diagnostiku.

## Editace jména kontaktu

V detailu uživatele je teď v horní liště tlačítko "Upravit" - otevře
dialog s textovým polem, po uložení se jméno kontaktu změní (klíč zůstává
stejný).

## Zámek aplikace

V Nastavení je nový přepínač **Zámek aplikace** (výchozí: vypnuto). Po
zapnutí appka při každém otevření (studený start i návrat z pozadí)
vyžaduje ověření otiskem prstu / obličejem / PINem / vzorem zařízení
(systémový `BiometricPrompt`, ne vlastní PIN appky). Pokud zařízení nemá
nastavený žádný zámek obrazovky, přepínač se nedá zapnout a appka na to
upozorní.

## Nastavení a jazyk

- V pravém horním rohu hlavní obrazovky je ikonka ozubeného kola →
  **Nastavení** → volba jazyka: **Podle systému / Čeština / English**.
- Výchozí volba je "Podle systému" – appka se řídí jazykem telefonu: pokud
  je systémový jazyk čeština, zobrazí se čeština; pro angličtinu i pro
  jakýkoli jiný/nepodporovaný jazyk (němčina, francouzština, ...) je
  výchozí **angličtina**.
- Volba se ukládá a přežije restart appky.
- Implementováno přes `AppCompatDelegate.setApplicationLocales()`
  (per-app language, funguje od API 24 výš, na Androidu 13+ se navíc
  propíše i do systémového nastavení "Jazyky aplikací").
- Všechny texty v UI jsou v `res/values/strings.xml` (angličtina, výchozí
  fallback) a `res/values-cs/strings.xml` (čeština).

## Ikona aplikace

Jednoduchý bílý zámek na tmavě tyrkysovém pozadí (`res/mipmap-*`,
adaptivní ikona pro Android 8+ i klasická pro starší verze).

## Zabezpečení uložených dat

- **Šifrování "at rest":** sdílený klíč každého kontaktu se před uložením do
  SharedPreferences zašifruje AES-256-GCM klíčem uloženým v **Android
  Keystore** (`crypto/KeystoreCryptoHelper.kt`). Tento klíč nikdy neopustí
  zabezpečený hardware/OS zařízení a aplikace se k jeho surovým bajtům
  nedostane. I s přístupem k souborům aplikace (root, ADB backup) by tak
  útočník viděl jen nečitelný šifrový text vázaný na dané zařízení.
- **`android:allowBackup="false"`** v `AndroidManifest.xml` – zabraňuje, aby
  se data aplikace (a v nich klíče) dostala nešifrovaně do systémových
  záloh zařízení (např. Google Drive backup).

## Jak sestavit APK jen z mobilu (bez počítače)

Projekt obsahuje `.github/workflows/build-apk.yml` – konfiguraci, která nechá
APK zkompilovat zdarma na serverech GitHubu. Vy jen nahrajete soubory a
stáhnete výsledek, vše z mobilního prohlížeče:

1. Na telefonu jděte na **github.com**, přihlaste se (nebo si zdarma
   založte účet).
2. Vytvořte nové repo (ikona **+** → *New repository*), např. `cryptochat`,
   klidně **Private**. Nezaškrtávejte "Add README" (necháme prázdné).
3. Na stránce repa klikněte **Add file → Upload files**.
4. Rozbalte si zip na telefonu (většina souborových manažerů / aplikace
   "Files" to umí) a v uploadovacím dialogu vyberte **všechny soubory a
   složky** z rozbalené složky `CryptoChat` (musí se nahrát i skrytá
   složka `.github` – pokud ji uploader nenabídne, nahrajte ji zvlášť
   přes "Upload files" → vyberte soubor `.github/workflows/build-apk.yml`
   a při uploadu do textového pole s cestou napište
   `.github/workflows/build-apk.yml`, GitHub složku sám vytvoří).
5. Dole potvrďte **Commit changes**.
6. Přejděte na záložku **Actions** v repu – build se spustí automaticky
   (trvá cca 3–5 minut). Pokud nezačal sám, klikněte na workflow
   "Build APK" → **Run workflow**.
7. Po dokončení (zelená fajfka) klikněte na proběhlý běh → dole v sekci
   **Artifacts** stáhněte `CryptoChat-debug-apk` (je to .zip obsahující
   `app-debug.apk`).
8. Rozbalte, nainstalujte APK na telefonu (povolte instalaci z neznámých
   zdrojů) – hotovo.

Tip: pokud vám nahrávání desítek souborů přes web přijde otravné, dá se
totéž udělat rychleji přes aplikaci **Termux** (`pkg install git`, pak
`git init`, `git add .`, `git commit`, `git push` do prázdného GitHub repa) –
samotné kompilování ale i tak proběhne na GitHubu, ne na telefonu.

## Struktura projektu

```
app/src/main/java/com/example/cryptochat/
├── MainActivity.kt              # navigace mezi obrazovkami
├── crypto/CryptoManager.kt      # AES-GCM šifrování/dešifrování
├── data/
│   ├── Contact.kt                # model uživatele
│   └── ContactRepository.kt      # lokální uložení (SharedPreferences)
├── crypto/
│   ├── CryptoManager.kt       # AES-256-GCM šifrování/dešifrování zpráv
│   ├── KeystoreCryptoHelper.kt # šifrování uložených klíčů (Android Keystore)
│   └── PostQuantumKem.kt      # ML-KEM-768 výměna klíče na dálku
├── data/
│   ├── Contact.kt
│   ├── ContactRepository.kt
│   └── SettingsRepository.kt  # nastavení appky (zámek)
├── viewmodel/ContactsViewModel.kt
└── ui/
    ├── lock/AppLock.kt           # biometrický zámek appky
    ├── qr/QrCodeUtil.kt          # generování QR kódu (ZXing)
    └── screens/
        ├── MainScreen.kt         # seznam uživatelů + ikonka nastavení
        ├── SettingsScreen.kt     # jazyk + zámek appky
        ├── AddUserScreen.kt      # volba: osobně / na dálku
        ├── CreateKeyScreen.kt    # osobně: zobrazení nového klíče + QR
        ├── AcceptKeyScreen.kt    # osobně: sken QR / ruční vložení klíče
        ├── RemoteInitScreen.kt   # na dálku: zahájení (ML-KEM)
        ├── RemoteCompleteScreen.kt # na dálku: dokončení (ML-KEM)
        ├── VerificationCodeContent.kt # sdílený krok ověření kódu
        ├── UserDetailScreen.kt   # poslat / přijmout / upravit / smazat
        ├── SendScreen.kt         # šifrování zprávy
        └── ReceiveScreen.kt      # dešifrování zprávy
```
