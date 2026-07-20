# CryptoChatServer

**English** | [Čeština](#cryptochatserver-česky)

CryptoChatServer is an Android messenger for **end-to-end encrypted** chat between two
people — with **no accounts, no phone numbers and no e-mail**. Messages and photos travel
through a "dead-drop" relay server that **never learns what you write, nor who is talking
to whom**. Tor is **built into the app**, so there is nothing extra to install.

## Download & install

1. Open [**Releases**](https://github.com/JelenXP/CryptoChatOnline/releases) and download
   the latest `app-release.apk`.
2. Open the file on your Android phone.
3. If prompted, allow installation from unknown sources.
4. Install — done. Requires **Android 8.0 (API 26)** or newer.

## First start

On the first launch a short wizard asks you to allow a few things so messages can arrive
while the app is closed:

- **Notifications** — so you know about new messages.
- **No battery restrictions** — so the system doesn't put the connection to sleep.
- **Run in the background / autostart** — some phones (Xiaomi, Huawei, Oppo, Vivo…) block
  apps from starting on their own.

Each step explains why it's needed and takes you straight to the right settings screen.

> The app keeps a quiet permanent notification while it's connected. That's the price of
> privacy: instead of using Google push (which would reveal *who* is messaging *whom*),
> the app holds its own connection over Tor.

## Adding a contact

Tap **+**, enter the person's name, and pick how you'll connect:

- **In person** — one phone shows the key (text + QR code), the other scans or pastes it.
  Trust comes from the physical channel.
- **Via invite (over the internet)** — one of you creates a **one-time invite code** and
  reads it to the other, who types it in. The connection then sets up automatically using
  a **post-quantum** key exchange (ML-KEM-768).

In both cases you then **compare a short verification code** out loud. Only if it matches
on both phones is the contact saved — that code is what protects you from a
man-in-the-middle.

## Chatting

- Write a message and send — it arrives almost instantly.
- Tap the **photo button** to send a picture from the **camera** or **gallery**. The photo
  is encrypted the same way; the other side sees it decrypted directly in the chat.
- The contact list shows the **last message** and highlights **unread** chats.
- The **cloud icon** at the top shows the connection: spinner while connecting, a cloud
  with a check when connected.

> The very first connection after opening the app takes roughly 10–20 seconds — Tor has to
> build its circuit. After that everything is fast, and the app keeps the connection warm.

## Backup

**Settings → Backup** exports everything into a single **password-protected** file:
contacts, keys, profile photos and your whole chat history (including photos). Importing
it — on this or another phone — restores exactly the same state.

> Keep the password safe. Without it the backup cannot be recovered.

## Own server

By default the app uses the built-in relay over Tor. In **Chat server** you can switch to
**Custom** and enter your own address (your own relay on your local network or your own
`.onion`).

## Security at a glance

- **AES-256-GCM** authenticated encryption of messages and photos.
- **Post-quantum** key exchange (ML-KEM-768, NIST FIPS 203) with a spoken verification code.
- **Built-in Tor** — the server never sees your IP address, and you install nothing extra.
- **The server is a blind dead-drop** — no accounts; mailbox IDs are derived from your
  shared key (which the server has never seen) and rotate daily, so the server cannot link
  a conversation together. Message length is hidden by padding.
- Server keeps everything **in memory only**, writes **no access logs**, and mailboxes are
  deleted the moment they're picked up.
- **Encryption at rest** — keys and chat history are wrapped by a non-exportable key in the
  Android Keystore.
- **App lock** — optional biometric / PIN lock on every open.
- **No cloud backup** of app data, and screenshots are blocked inside the app.

## What it does *not* hide

The server can see that *some* mailbox received *something* at a given time (otherwise it
couldn't deliver). It cannot tell whose it is or what's in it.

## Language

The app follows your phone's language automatically; you can also force
**System / Čeština / English** in Settings.

---

# CryptoChatServer (česky)
<a name="cryptochatserver-česky"></a>

[English](#cryptochatserver) | **Čeština**

CryptoChatServer je Android messenger pro **end-to-end šifrovaný** chat mezi dvěma lidmi —
**bez účtů, telefonních čísel a e-mailů**. Zprávy i fotky chodí přes „slepou schránku"
(relay server), který **nikdy nezná obsah ani to, kdo komu píše**. Tor je **zabudovaný
přímo v aplikaci**, takže není potřeba nic dalšího instalovat.

## Stažení a instalace

1. Otevři [**Releases**](https://github.com/JelenXP/CryptoChatOnline/releases) a stáhni
   poslední `app-release.apk`.
2. Soubor otevři na Android telefonu.
3. Případně povol instalaci z neznámých zdrojů.
4. Nainstaluj — hotovo. Vyžaduje **Android 8.0 (API 26)** nebo novější.

## První spuštění

Při prvním spuštění tě krátký průvodce provede povoleními, aby zprávy chodily i když je
appka zavřená:

- **Oznámení** — abys věděl o nových zprávách.
- **Bez omezení baterií** — aby systém spojení neuspával.
- **Běh na pozadí / autostart** — některé telefony (Xiaomi, Huawei, Oppo, Vivo…) brání
  appkám ve spuštění na pozadí.

U každého kroku je vysvětlené proč a tlačítko, které tě pošle rovnou do správného nastavení.

> Dokud je appka připojená, drží tichou trvalou notifikaci. To je cena za soukromí: místo
> Google pushe (který by prozradil, *kdo* komu píše) si drží vlastní spojení přes Tor.

## Přidání kontaktu

Ťukni na **+**, zadej jméno a vyber, jak se spojíte:

- **Osobně** — jeden telefon ukáže klíč (text + QR kód), druhý ho naskenuje nebo vloží.
  Důvěra plyne z fyzického kanálu.
- **Na pozvánku (přes internet)** — jeden z vás vytvoří **jednorázový pozvánkový kód** a
  řekne ho druhému, ten ho zadá. Spojení se pak naváže automaticky **post-kvantovou**
  výměnou klíče (ML-KEM-768).

V obou případech si pak **nahlas porovnáte krátký ověřovací kód**. Kontakt se uloží, jen
když se na obou telefonech shoduje — právě ten kód tě chrání před útokem
man-in-the-middle.

## Chatování

- Napiš zprávu a odešli — dorazí skoro okamžitě.
- Tlačítkem **fotky** pošleš obrázek z **foťáku** nebo **galerie**. Fotka se šifruje stejně
  jako zprávy a druhá strana ji vidí rovnou dešifrovanou přímo v chatu.
- V seznamu kontaktů je vidět **poslední zpráva** a **nepřečtené** chaty jsou zvýrazněné.
- **Ikona cloudu** nahoře ukazuje spojení: kolečko při připojování, cloud s fajfkou po
  připojení.

> Úplně první připojení po otevření appky trvá zhruba 10–20 sekund — Tor musí postavit
> okruh. Pak už je vše rychlé a appka drží spojení teplé.

## Záloha

**Nastavení → Záloha** vyexportuje všechno do jednoho souboru **chráněného heslem**:
kontakty, klíče, profilové fotky i celou historii chatů (včetně fotek). Import — na tomhle
nebo jiném telefonu — obnoví přesně stejný stav.

> Heslo si dobře ulož. Bez něj zálohu nelze obnovit.

## Vlastní server

Ve výchozím stavu appka používá zabudovaný relay přes Tor. V **Serveru chatu** můžeš
přepnout na **Vlastní** a zadat svou adresu (vlastní relay v místní síti nebo vlastní
`.onion`).

## Zabezpečení ve zkratce

- **AES-256-GCM** autentizované šifrování zpráv i fotek.
- **Post-kvantová** výměna klíče (ML-KEM-768, NIST FIPS 203) s nahlas ověřovaným kódem.
- **Zabudovaný Tor** — server nevidí tvoji IP adresu a ty nic dalšího neinstaluješ.
- **Server je slepá schránka** — žádné účty; ID schránek se odvozují ze sdíleného klíče
  (který server nikdy neviděl) a každý den se mění, takže server nespojí konverzaci
  dohromady. Délku zprávy skrývá padding.
- Server drží vše **jen v paměti**, nevede **žádné access logy** a schránky mizí hned po
  vyzvednutí.
- **Šifrování „at rest"** — klíče i historie chatů jsou zabalené neexportovatelným klíčem
  v Android Keystore.
- **Zámek aplikace** — volitelné biometrické / PIN ověření při každém otevření.
- **Bez cloudových záloh** dat aplikace a uvnitř appky jsou blokované screenshoty.

## Co to *neskryje*

Server vidí, že *nějaká* schránka v nějaký čas *něco* dostala (jinak by nedoručil).
Nepozná ale čí je ani co v ní je.

## Jazyk

Appka se řídí jazykem telefonu automaticky; v Nastavení lze vynutit
**Podle systému / Čeština / English**.
