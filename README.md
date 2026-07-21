# CryptoChat

**English** | [Čeština](#cryptochat-česky)

CryptoChat is an Android app for **end-to-end encrypted** messages and files between two
people. It has **no network layer at all** — an encrypted message is just a Base64 string
(and an encrypted file is just a file) that you send over any channel you already use
(SMS, e-mail, any messenger), and the other person decrypts it. All contacts and keys live
**only on your device**.

> Looking for a real-time messenger instead? There is a companion app,
> **CryptoChatServer**, which delivers messages over a zero-knowledge relay with Tor built
> in — see the `CryptoChatServer/Client/` folder.

## Download & install

1. Open [**Releases**](https://github.com/JelenXP/CryptoChat/releases) and download the
   latest `app-release.apk`.
2. Open the file on your Android phone.
3. If prompted, allow installation from unknown sources.
4. Install — done. Requires **Android 8.0 (API 26)** or newer.

## Adding a contact

Tap **+**, enter the person's name, and choose how you'll exchange the secret key:

- **In person** — one phone shows the key (as text and a QR code), the other scans or
  pastes it. Trust comes from the physical channel.
- **Remotely** — a serverless **post-quantum** key exchange (ML-KEM-768). One side starts
  and sends a public key, the other completes and replies.

Then **both sides confirm a short verification code out loud** (e.g. over the phone)
before the contact is saved — that code protects you against a man-in-the-middle.

You can later **verify a contact** any time (compare a key fingerprint) or **renew the
key** if you need a fresh one.

## Sending and receiving

Open a contact and you get everything in one place:

- **Text** — write a message, encrypt it, and copy or **share** the result to any app.
  To read one, paste the encrypted text (or use **Share to CryptoChat** from another app)
  and decrypt.
- **Files** — encrypt a photo, document or any file with the same shared key. The other
  side picks the encrypted file and decrypts it, with a preview, saving and sharing.

## Contacts

Profile photos, search, quick actions on long press, and a **backup**: export all contacts
and keys into a single **password-protected** file and import it back — on this or another
phone.

## Security at a glance

- **AES-256-GCM** authenticated encryption (confidentiality *and* integrity). A tampered
  message or the wrong key fails to decrypt — by design.
- **Nothing leaves your device automatically** — the app has no network access apart from
  an optional update check. You are the transport.
- **Post-quantum** remote key exchange (ML-KEM-768, NIST FIPS 203) with a spoken
  verification code.
- **Encryption at rest** — each contact's key is wrapped by a non-exportable key held in
  the Android Keystore before it is stored.
- **App lock** — optional biometric / device-credential lock on every open.
- **No cloud backup** of app data (`allowBackup=false`), and screenshots are blocked
  inside the app.

## Personalisation

**Settings → Design** lets you change theme (system / light / dark), accent colour,
density, corner style and transition animations. Update reminders can be paused or turned
off completely.

## Language

The app follows your phone's language automatically; you can also force
**System / Čeština / English** in Settings.

---

# CryptoChat (česky)
<a name="cryptochat-česky"></a>

[English](#cryptochat) | **Čeština**

CryptoChat je Android aplikace pro **end-to-end šifrované** zprávy a soubory mezi dvěma
lidmi. Nemá **vůbec žádnou síťovou vrstvu** — zašifrovaná zpráva je jen Base64 řetězec
(a zašifrovaný soubor je prostě soubor), který pošleš libovolným kanálem, který už používáš
(SMS, e-mail, jakýkoli messenger), a druhá strana ho dešifruje. Všechny kontakty a klíče
leží **jen na tvém zařízení**.

> Hledáš spíš messenger v reálném čase? Existuje sesterská appka **CryptoChatServer**, která
> doručuje zprávy přes zero-knowledge relay se zabudovaným Torem — viz složka
> `CryptoChatServer/Client/`.

## Stažení a instalace

1. Otevři [**Releases**](https://github.com/JelenXP/CryptoChat/releases) a stáhni poslední
   `app-release.apk`.
2. Soubor otevři na Android telefonu.
3. Případně povol instalaci z neznámých zdrojů.
4. Nainstaluj — hotovo. Vyžaduje **Android 8.0 (API 26)** nebo novější.

## Přidání kontaktu

Ťukni na **+**, zadej jméno a zvol, jak si vyměníte tajný klíč:

- **Osobně** — jeden telefon klíč zobrazí (jako text a QR kód), druhý ho naskenuje nebo
  vloží. Důvěra plyne z fyzického kanálu.
- **Na dálku** — bezserverová **post-kvantová** výměna klíče (ML-KEM-768). Jedna strana
  zahájí a pošle veřejný klíč, druhá dokončí a odpoví.

Pak si **obě strany nahlas potvrdí krátký ověřovací kód** (např. po telefonu), teprve
potom se kontakt uloží — ten kód chrání proti útoku man-in-the-middle.

Kontakt jde kdykoli později **ověřit** (porovnání otisku klíče) nebo mu **obnovit klíč**.

## Odesílání a příjem

Po otevření kontaktu máš vše na jednom místě:

- **Text** — napiš zprávu, zašifruj ji a výsledek zkopíruj nebo **sdílej** do jakékoli
  appky. Přijatou zprávu vlož (nebo použij **Sdílet do CryptoChat** z jiné appky)
  a dešifruj.
- **Soubory** — zašifruj fotku, dokument nebo cokoli jiného stejným sdíleným klíčem. Druhá
  strana zašifrovaný soubor vybere a dešifruje — s náhledem, uložením i sdílením.

## Kontakty

Profilové fotky, vyhledávání, rychlé akce po dlouhém stisku a **záloha**: export všech
kontaktů a klíčů do jednoho souboru **chráněného heslem** a import zpátky — na tomhle nebo
jiném telefonu.

## Zabezpečení ve zkratce

- **AES-256-GCM** autentizované šifrování (důvěrnost *i* integrita). Poškozená zpráva nebo
  špatný klíč se záměrně nedešifrují.
- **Nic neodchází automaticky** — appka nemá přístup k síti kromě volitelné kontroly
  aktualizací. Přenos zajišťuješ ty.
- **Post-kvantová** výměna klíče na dálku (ML-KEM-768, NIST FIPS 203) s nahlas ověřovaným
  kódem.
- **Šifrování „at rest"** — klíč každého kontaktu je před uložením zabalen neexportovatelným
  klíčem v Android Keystore.
- **Zámek aplikace** — volitelné biometrické / PIN ověření při každém otevření.
- **Bez cloudových záloh** dat aplikace (`allowBackup=false`) a uvnitř appky jsou blokované
  screenshoty.

## Přizpůsobení

V **Nastavení → Vzhled** změníš motiv (podle systému / světlý / tmavý), barvu akcentu,
hustotu, styl rohů i animace přechodů. Připomínání aktualizací lze pozastavit nebo úplně
vypnout.

## Jazyk

Appka se řídí jazykem telefonu automaticky; v Nastavení lze vynutit
**Podle systému / Čeština / English**.
