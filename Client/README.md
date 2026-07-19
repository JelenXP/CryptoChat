# CryptoChat

**English** | [Čeština](#cryptochat-česky)

CryptoChat is an Android app for **end-to-end encrypted** text messages between two
people. It has **no network layer at all** — an encrypted message is just a short
Base64 string that you copy and send over any channel you already use (SMS, e-mail,
any messenger), and the other person pastes it back to decrypt. All contacts and keys
live **only on your device**.

## Download & install

1. Open [**Releases**](https://github.com/JelenXP/CryptoChat/releases) and download the
   latest `app-release.apk`.
2. Open the file on your Android phone.
3. If prompted, allow installation from unknown sources.
4. Install — done. Requires **Android 8.0 (API 26)** or newer.

## How to use

1. **Add a contact** — tap **+**, enter the person's name, and choose how you'll
   exchange the secret key:
   - **In person** — one phone shows the key (as text and a QR code), the other scans
     or pastes it. Trust comes from the physical channel.
   - **Remotely** — a serverless **post-quantum** key exchange (ML‑KEM‑768). One side
     starts and sends a public key, the other completes and replies. Then **both sides
     confirm a short verification code out loud** (e.g. over the phone) before the
     contact is saved — that code protects you against a man‑in‑the‑middle.
2. **Send** — type a message, tap **Encrypt**, copy the Base64 result and send it
   however you like.
3. **Receive** — paste the Base64 you received, tap **Decrypt**, and read the message.

## Security at a glance

- **AES‑256‑GCM** authenticated encryption (confidentiality *and* integrity). A tampered
  message or the wrong key fails to decrypt — by design.
- **Nothing leaves your device automatically** — the app has no network access. You are
  the transport.
- **Post‑quantum** remote key exchange (ML‑KEM‑768, NIST FIPS 203) with a spoken
  verification code.
- **Encryption at rest** — each contact's key is wrapped by a non‑exportable key held in
  the Android Keystore before it is stored.
- **App lock** — optional biometric / device‑credential lock on every open.
- **No cloud backup** of app data (`allowBackup=false`).

## Language

The app follows your phone's language automatically; you can also force
**System / Čeština / English** in Settings.

---

# CryptoChat (česky)
<a name="cryptochat-česky"></a>

[English](#cryptochat) | **Čeština**

CryptoChat je Android aplikace pro **end‑to‑end šifrované** textové zprávy mezi dvěma
lidmi. Nemá **vůbec žádnou síťovou vrstvu** — zašifrovaná zpráva je jen krátký Base64
řetězec, který zkopíruješ a pošleš libovolným kanálem, který už používáš (SMS, e‑mail,
jakýkoli messenger), a druhá strana ho vloží zpět k dešifrování. Všechny kontakty a
klíče leží **jen na tvém zařízení**.

## Stažení a instalace

1. Otevři [**Releases**](https://github.com/JelenXP/CryptoChat/releases) a stáhni
   poslední `app-release.apk`.
2. Soubor otevři na Android telefonu.
3. Případně povol instalaci z neznámých zdrojů.
4. Nainstaluj — hotovo. Vyžaduje **Android 8.0 (API 26)** nebo novější.

## Jak se používá

1. **Přidej kontakt** — ťukni na **+**, zadej jméno a zvol, jak si vyměníte tajný klíč:
   - **Osobně** — jeden telefon klíč zobrazí (jako text a QR kód), druhý ho naskenuje
     nebo vloží. Důvěra plyne z fyzického kanálu.
   - **Na dálku** — bezserverová **post‑kvantová** výměna klíče (ML‑KEM‑768). Jedna
     strana zahájí a pošle veřejný klíč, druhá dokončí a odpoví. Pak si **obě strany
     nahlas potvrdí krátký ověřovací kód** (např. po telefonu), teprve potom se kontakt
     uloží — ten kód chrání proti útoku man‑in‑the‑middle.
2. **Poslat** — napiš zprávu, ťukni **Zašifrovat**, zkopíruj Base64 výsledek a pošli ho,
   jak chceš.
3. **Přijmout** — vlož obdržený Base64, ťukni **Dešifrovat** a přečti si zprávu.

## Zabezpečení ve zkratce

- **AES‑256‑GCM** autentizované šifrování (důvěrnost *i* integrita). Poškozená zpráva
  nebo špatný klíč se záměrně nedešifrují.
- **Nic neodchází automaticky** — appka nemá přístup k síti. Přenos zajišťuješ ty.
- **Post‑kvantová** výměna klíče na dálku (ML‑KEM‑768, NIST FIPS 203) s nahlas
  ověřovaným kódem.
- **Šifrování „at rest"** — klíč každého kontaktu je před uložením zabalen
  neexportovatelným klíčem v Android Keystore.
- **Zámek aplikace** — volitelné biometrické / PIN ověření při každém otevření.
- **Bez cloudových záloh** dat aplikace (`allowBackup=false`).

## Jazyk

Appka se řídí jazykem telefonu automaticky; v Nastavení lze vynutit
**Podle systému / Čeština / English**.
