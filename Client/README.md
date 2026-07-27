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

Tap **+**, enter the person's name, and pick your role:

- **Create an invite** — your phone shows a **one-time invite code** as text *and* as a
  **QR code**.
- **Enter an invite** — the other person types the code in, or taps **Scan QR** and points
  the camera at the first phone. Scanning starts the pairing straight away.

The connection then sets itself up using a **post-quantum** key exchange (ML-KEM-768).
Afterwards you **compare a short verification code** out loud. Only if it matches on both
phones is the contact saved — that code is what protects you from a man-in-the-middle.

Once you've compared it, open the contact and tap **Mark as verified**. The app remembers
it, and if that person's security code ever changes later, the conversation warns you.

## Chatting

- Write a message and send — it arrives almost instantly.
- Tap the **photo button** to send a picture from the **camera** or **gallery**. The photo
  is encrypted the same way; the other side sees it decrypted directly in the chat.
- **Reply** — swipe a message from left to right, or hold it and pick Reply. The original
  is quoted above your answer. **Tap the quote** to jump to the original message, which
  lights up briefly.
- **React** — hold a message and pick one of six emoji. Tapping the same one removes it.
  Both phones need a recent version; until then reactions simply aren't sent.
- **Edit** — hold one of your own text messages and pick Edit; fix it and send. The other
  phone updates it too and the message is marked **"edited"**. Older versions keep the
  original text.
- **Copy or delete** — holding a message lets you copy its text or delete it; a deleted
  message turns into a grey **"Deleted"** in place of the bubble. For your own messages you
  can **Delete for everyone**, so it becomes "Deleted" on both phones; **Delete for me**
  does the same just on this phone.
- **Day separators** mark where each day starts (Today / Yesterday / date), and a
  **"New messages"** line shows where you left off.
- Scroll up and a **jump-to-latest button** appears, with a count of messages that arrived
  meanwhile.
- **Unfinished text is kept.** Leave the conversation — or restart the app — and it's still
  there. The contact list marks such chats with **Draft**.
- **Straight from the notification** — a new-message notification shows all the unseen
  messages as a conversation. You can **Reply** right there (it's sent as a normal message)
  or tap **Like** to put a 👍 on the last one. Opening the chat clears its notification.
- The contact list shows the **last message**, highlights **unread** chats, and keeps the
  **most recently active** conversation at the top.
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

You can also list **backup servers**, one address per line. If the main one doesn't answer,
messages go out over a backup — and the app looks for incoming ones there too. Because a
mailbox address is derived from your shared key, the same mailbox exists on every server,
so the two phones don't have to agree on which one to use.

**Settings → Connection diagnostics** shows how Tor is starting up, how the server is
responding (success rate, response time) and the last few log lines. Server addresses and
mailbox identifiers are never shown there.

## Security at a glance

- **AES-256-GCM** authenticated encryption of messages and photos.
- **Post-quantum** key exchange (ML-KEM-768, NIST FIPS 203) with a spoken verification code.
- **Automatic key rotation** — keys refresh themselves as you chat (a forward-secret
  ratchet, plus a periodic post-quantum re-key that heals after a possible leak), with no
  re-pairing. The contact detail shows the current state and an optional **security code**
  you can compare to confirm you're both on the same fresh key.
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

## Reporting a problem

Found a bug? **Settings → Report a problem.** Describe what went wrong, tick what you
want to attach (app and phone version, diagnostic log, connection state, last crash
record) and send it. Before sending you can **read exactly what will be submitted**.

Nothing sensitive is ever collected: no messages, encryption keys, contact names,
mailbox IDs or the server address. The report goes out **over Tor**, so it never
reveals your IP address — if Tor isn't running, nothing is sent.

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

Ťukni na **+**, zadej jméno a vyber svou roli:

- **Vytvořit pozvánku** — tvůj telefon ukáže **jednorázový pozvánkový kód** jako text *i*
  jako **QR kód**.
- **Zadat pozvánku** — druhý ho buď opíše, nebo ťukne na **Naskenovat QR** a namíří foťák
  na první telefon. Naskenováním se párování rovnou spustí.

Spojení se pak naváže automaticky **post-kvantovou** výměnou klíče (ML-KEM-768). Potom si
**nahlas porovnáte krátký ověřovací kód**. Kontakt se uloží, jen když se na obou telefonech
shoduje — právě ten kód tě chrání před útokem man-in-the-middle.

Až si kód porovnáte, otevři kontakt a ťukni na **Označit jako ověřený**. Appka si to
zapamatuje, a kdyby se bezpečnostní kód toho člověka někdy později změnil, konverzace tě
varuje.

## Chatování

- Napiš zprávu a odešli — dorazí skoro okamžitě.
- Tlačítkem **fotky** pošleš obrázek z **foťáku** nebo **galerie**. Fotka se šifruje stejně
  jako zprávy a druhá strana ji vidí rovnou dešifrovanou přímo v chatu.
- **Odpověď** — přetáhni zprávu zleva doprava, nebo ji podrž a zvol Odpovědět. Původní
  zpráva se ukáže nad tvojí odpovědí. **Ťuknutím na citaci** skočíš na původní zprávu,
  která se na chvíli rozsvítí.
- **Reakce** — podrž zprávu a vyber jedno ze šesti emoji. Stejné podruhé reakci zruší.
  Oba telefony potřebují novější verzi; do té doby se reakce prostě neposílají.
- **Úprava** — podrž svoji textovou zprávu a zvol Upravit; oprav ji a odešli. Druhý telefon
  ji přepíše taky a zpráva se označí **„upraveno"**. Starší verze si nechá původní text.
- **Kopírování a mazání** — po podržení zprávy jde zkopírovat text nebo zprávu smazat;
  smazaná zpráva se místo bubliny změní na šedé **„Smazáno"**. U vlastních zpráv jde
  **Smazat pro všechny**, takže „Smazáno" bude na obou telefonech; **Smazat u mě** udělá
  totéž jen na tomhle telefonu.
- **Oddělovače dnů** ukazují, kde začíná který den (Dnes / Včera / datum), a čára
  **„Nové zprávy"** označí, kde jsi skončil.
- Když odroluješ nahoru, objeví se **tlačítko skoku na konec** s počtem zpráv, které mezitím
  dorazily.
- **Rozepsaný text se uchová.** Odejdeš z konverzace — nebo restartuješ appku — a pořád tam
  je. V seznamu kontaktů se takový chat označí jako **Rozepsáno**.
- **Přímo z oznámení** — oznámení o nové zprávě ukáže všechny nepřečtené zprávy jako
  konverzaci. Můžeš na něj rovnou **Odpovědět** (odešle se jako normální zpráva) nebo
  ťuknout na **To se mi líbí** a dát 👍 na poslední. Otevřením chatu oznámení zmizí.
- V seznamu kontaktů je vidět **poslední zpráva**, **nepřečtené** chaty jsou zvýrazněné
  a **naposledy aktivní** konverzace je nahoře.
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

Můžeš taky vypsat **záložní servery**, jednu adresu na řádek. Když hlavní neodpovídá,
zprávy odejdou přes záložní — a appka tam hledá i příchozí. Adresa schránky se odvozuje ze
sdíleného klíče, takže stejná schránka existuje na každém serveru a telefony se nemusí
domlouvat, který zrovna použít.

**Nastavení → Diagnostika připojení** ukáže, jak se rozjíždí Tor, jak odpovídá server
(úspěšnost, doba odezvy) a posledních pár řádků logu. Adresy serverů ani identifikátory
schránek se tam nikdy nezobrazují.

## Zabezpečení ve zkratce

- **AES-256-GCM** autentizované šifrování zpráv i fotek.
- **Post-kvantová** výměna klíče (ML-KEM-768, NIST FIPS 203) s nahlas ověřovaným kódem.
- **Automatická rotace klíčů** — klíče se během psaní samy obměňují (forward-secret ratchet
  plus občasný post-kvantový re-key, který uzdraví po možném úniku), bez nutnosti znovu se
  párovat. Detail kontaktu ukazuje aktuální stav a volitelný **bezpečnostní kód**, který si
  můžeš porovnat a ověřit, že máte oba stejný čerstvý klíč.
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

## Hlášení chyby

Narazil jsi na chybu? **Nastavení → Nahlásit chybu.** Popiš, co se pokazilo, zaškrtni,
co se má přiložit (verze aplikace a telefonu, diagnostický log, stav připojení, záznam
o posledním pádu) a odešli. Před odesláním si můžeš **přečíst přesně to, co se odešle**.

Nic citlivého se nesbírá: žádné zprávy, šifrovací klíče, jména kontaktů, ID schránek
ani adresa serveru. Hlášení jde **přes Tor**, takže neprozradí tvou IP adresu — když
Tor neběží, neodešle se nic.

## Jazyk

Appka se řídí jazykem telefonu automaticky; v Nastavení lze vynutit
**Podle systému / Čeština / English**.
