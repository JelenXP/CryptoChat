# CryptoChat

**A private, end-to-end encrypted messenger for two people — no accounts, no phone number, no e-mail.**

![Platform: Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![Version](https://img.shields.io/badge/version-2.4.1-1abc9c)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

**English** · [Čeština](#cryptochat-česky)

---

CryptoChat is a small, privacy-first Android messenger. Messages and photos are
**end-to-end encrypted** on your phone and travel through a **"dead-drop" relay
server that never learns what you write, nor who is talking to whom**. There are
**no accounts** — you pair with someone by scanning a one-time invite. **Tor is
built into the app**, so there's nothing extra to install and the server never
sees your IP address.

It's a personal, open-source project focused on privacy rather than features or
polish. There's no marketing spin here — quality is kept in check by an automated
test suite, not by promises. This repository contains **two things**: the Android
app (in [`Client/`](Client/)) and the relay server (`server.py`, a tiny
zero-knowledge dead-drop).

## Why it's different

- **The server is blind.** It's a dumb mailbox: it stores and hands back encrypted
  blobs and nothing else. No accounts, no address book, no metadata about who talks
  to whom.
- **No Google push.** Most messengers use push services that reveal *who* is
  messaging *whom*. CryptoChat instead holds its own connection over Tor — that's
  the price of the quiet permanent notification you'll see while it's connected.
- **Post-quantum key exchange.** Pairing uses ML-KEM-768 (NIST FIPS 203), so a
  future quantum computer can't retroactively break a captured handshake.
- **You verify each other in person.** After pairing you read a short code aloud;
  the contact is only saved if it matches on both phones. That's your protection
  against a man-in-the-middle.

## Privacy & security

### What the server can and cannot see

| | Sees it? | How that's ensured |
|---|---|---|
| Message / photo content | **No, never** | End-to-end AES-256-GCM encryption happens on your phone |
| Who is the sender / recipient | **No, never** | No accounts — only anonymous mailbox IDs |
| That "A is talking to B" | **No, never** | Mailbox ID = HKDF of a shared key the server never saw; it also rotates daily |
| Message length | **No (hidden)** | The app pads messages, photos and files to fixed sizes |
| Your IP address | **Hidden via Tor** | Reached as a Tor onion service; nothing extra to install |
| That *some* mailbox received *something* | Yes | Can't be fully hidden — see the threat model below |

The server keeps everything **in memory only** (nothing on disk), writes **no
access logs**, and mailboxes have a short lifetime (TTL) and are **deleted the
moment they're picked up**.

### On top of that

- **Automatic key rotation** — keys refresh themselves as you chat (a forward-secret
  ratchet plus a periodic post-quantum re-key that heals after a possible leak),
  with no re-pairing. The contact screen shows the current state and a security code
  you can compare to confirm you're both on the same fresh key.
- **Encryption at rest** — keys and chat history are wrapped by a non-exportable key
  in the Android Keystore.
- **App lock** — optional biometric / PIN lock on every open.
- **No cloud backup** of app data, and screenshots are blocked inside the app.

### What it does *not* hide (honestly)

- The server can tell that *some* mailbox received *something* at a given time —
  otherwise it couldn't deliver. Hiding the fact that communication happens at all
  would need cover traffic (fake messages); that's not implemented.
- A **global passive observer** who can watch both sides of the network at once can
  attempt timing correlation. Tor makes this harder, not impossible.
- A **compromised phone** means the keys are on it. That's what the app lock and
  Keystore defend, not the relay.

## Features

- End-to-end encrypted **text, photos and files**.
- **Reactions**, **replies** (with jump-to-original), **edit** and **delete for
  everyone**.
- **Text formatting** — `*bold*`, `_italics_`, `~strikethrough~`, `` `monospace` ``.
- Day separators, a "new messages" line, jump-to-latest, and **drafts that survive
  a restart**.
- **Reply straight from the notification**, or tap Like to react.
- **Profile photos**, contact search, and unread highlights.
- **Runs in the background** — a foreground service keeps the Tor connection warm
  and delivers notifications while sparing the battery.
- **Password-protected backup & restore** of everything: contacts, keys, profile
  photos and full chat history (including photos).
- **Update check over Tor** — so checking for a new version never reveals your IP.
- Follows your phone's language, or force **System / Čeština / English**.

## Get it

1. Open the [**Releases**](https://github.com/JelenXP/CryptoChatServer-releases/releases)
   page and download the latest `app-release.apk` (currently **2.4.1**).
2. Open the file on your Android phone; allow installation from unknown sources if
   prompted.
3. Install. Requires **Android 8.0 (API 26)** or newer.

The APK is signed, and the app can update itself in place. You can also **build it
from source** — see the developer notes in [`Client/`](Client/) if you're curious.

## Self-hosting the relay

By default the app uses a built-in relay over Tor, so you don't need a server at
all. But the relay (`server.py`) is deliberately tiny — pure Python 3 standard
library, no dependencies — and runs on anything: a single-board computer, an old
PC, a VPS. Point the app at your own address under **Chat server → Custom**.

Full setup, the HTTP API, Tor onion-service configuration and the complete
"what the server sees" breakdown are in **[SERVER.md](SERVER.md)**.

## Open source & license

CryptoChat is free and open-source software, licensed under the
**[GNU Affero General Public License v3.0](LICENSE)** (AGPL-3.0). You are free to
use, study, share and modify it; if you run a modified version as a network
service (for example, a modified relay), you must offer its source to your users.
See [LICENSE](LICENSE) for the full terms.

Copyright © 2026 JelenXP.

## A note on privacy

There are **no accounts, no phone numbers and no e-mail** — nothing ties your
identity to a conversation. If you'd rather not run any server at all, there's a
sibling project, [**CryptoChatOffline**](https://github.com/JelenXP/CryptoChatOffline):
a fully offline app that encrypts text and files into copy-paste blobs you send
over any channel. It lives in its own repository and is not part of this one.

---
---

# CryptoChat (česky)

**Soukromý, end-to-end šifrovaný messenger pro dva lidi — bez účtů, telefonního čísla i e-mailu.**

[English](#cryptochat) · **Čeština**

---

CryptoChat je malý Android messenger s důrazem na soukromí. Zprávy a fotky se
**end-to-end šifrují** přímo v telefonu a chodí přes **„slepou schránku" (relay
server), který nikdy nezná obsah ani to, kdo komu píše**. **Žádné účty** — s druhým
člověkem se spárujete naskenováním jednorázové pozvánky. **Tor je zabudovaný přímo
v aplikaci**, takže není potřeba nic dalšího instalovat a server nikdy nevidí vaši
IP adresu.

Je to osobní open-source projekt zaměřený na soukromí, ne na množství funkcí ani
uhlazenost. Žádný marketing — kvalitu hlídá sada automatických testů, ne sliby.
Tenhle repozitář obsahuje **dvě věci**: Android appku (ve složce
[`Client/`](Client/)) a relay server (`server.py`, drobnou zero-knowledge
schránku).

## V čem je jiný

- **Server je slepý.** Je to hloupá schránka: jen ukládá a vydává zašifrované blobky,
  nic víc. Žádné účty, žádný adresář, žádná metadata o tom, kdo s kým mluví.
- **Žádný Google push.** Většina messengerů používá push služby, které prozradí,
  *kdo* komu píše. CryptoChat si místo toho drží vlastní spojení přes Tor — to je
  cena za tichou trvalou notifikaci, kterou uvidíte, dokud je připojený.
- **Post-kvantová výměna klíče.** Párování používá ML-KEM-768 (NIST FIPS 203), takže
  ani budoucí kvantový počítač zpětně nerozlomí zachycený handshake.
- **Ověříte se navzájem osobně.** Po spárování si nahlas přečtete krátký kód; kontakt
  se uloží, jen když se na obou telefonech shoduje. To je vaše obrana proti útoku
  man-in-the-middle.

## Soukromí a bezpečnost

### Co server vidí a co ne

| | Vidí to? | Jak to zajišťujeme |
|---|---|---|
| Obsah zprávy / fotky | **Ne, nikdy** | End-to-end šifrování AES-256-GCM proběhne v telefonu |
| Kdo je odesílatel / příjemce | **Ne, nikdy** | Žádné účty — jen anonymní ID schránky |
| Že „A píše B" | **Ne, nikdy** | ID schránky = HKDF ze sdíleného klíče, který server nikdy neviděl; navíc se každý den rotuje |
| Délka zprávy | **Ne (skryjeme)** | Appka zprávy, fotky i soubory paddinguje na fixní velikosti |
| Vaše IP adresa | **Skrytá přes Tor** | Dostupné jako Tor onion service; nic dalšího se neinstaluje |
| Že *nějaká* schránka *něco* dostala | Ano | Skrýt úplně nejde — viz threat model níže |

Server drží vše **jen v paměti** (nic na disk), nevede **žádné access logy** a
schránky mají krátkou životnost (TTL) a **mažou se hned po prvním vyzvednutí**.

### Navíc k tomu

- **Automatická rotace klíčů** — klíče se během psaní samy obměňují (forward-secret
  ratchet plus občasný post-kvantový re-key, který uzdraví po možném úniku), bez
  nutnosti znovu se párovat. Detail kontaktu ukazuje aktuální stav a bezpečnostní
  kód, který si můžete porovnat a ověřit, že máte oba stejný čerstvý klíč.
- **Šifrování „at rest"** — klíče i historie chatů jsou zabalené neexportovatelným
  klíčem v Android Keystore.
- **Zámek aplikace** — volitelné biometrické / PIN ověření při každém otevření.
- **Bez cloudových záloh** dat aplikace a uvnitř appky jsou blokované screenshoty.

### Co to *neskryje* (na rovinu)

- Server pozná, že *nějaká* schránka v nějaký čas *něco* dostala — jinak by
  nedoručil. Skrýt „že se vůbec komunikuje" by chtělo cover traffic (falešné
  zprávy); to zatím není.
- **Globální pasivní odposlech**, který vidí síť obou stran zároveň, může zkoušet
  časovou korelaci. Tor to ztěžuje, ne však dokonale.
- **Kompromitovaný telefon** znamená, že klíče jsou v něm. To brání zámek aplikace
  a Keystore, ne relay.

## Funkce

- End-to-end šifrovaný **text, fotky a soubory**.
- **Reakce**, **odpovědi** (se skokem na původní zprávu), **úprava** a **smazání pro
  všechny**.
- **Formátování textu** — `*tučně*`, `_kurzíva_`, `~přeškrtnutí~`, `` `strojopis` ``.
- Oddělovače dnů, čára „nové zprávy", skok na konec a **rozepsaný text, který
  přežije restart**.
- **Odpověď přímo z oznámení**, nebo ťuknutím na To se mi líbí.
- **Profilové fotky**, hledání kontaktů a zvýraznění nepřečtených.
- **Běží na pozadí** — foreground service drží Tor spojení teplé a doručuje
  oznámení, přitom šetří baterii.
- **Záloha a obnova chráněná heslem** — úplně všeho: kontaktů, klíčů, profilových
  fotek i celé historie chatů (včetně fotek).
- **Kontrola aktualizací přes Tor** — takže kontrola nové verze nikdy neprozradí
  vaši IP.
- Řídí se jazykem telefonu, nebo vynutíte **Podle systému / Čeština / English**.

## Jak ji získat

1. Otevři stránku [**Releases**](https://github.com/JelenXP/CryptoChatServer-releases/releases)
   a stáhni poslední `app-release.apk` (aktuálně **2.4.1**).
2. Soubor otevři na Android telefonu; případně povol instalaci z neznámých zdrojů.
3. Nainstaluj. Vyžaduje **Android 8.0 (API 26)** nebo novější.

APK je podepsané a appka se umí aktualizovat sama. Můžeš ji taky **sestavit ze
zdrojů** — pro zvědavé jsou vývojářské poznámky ve složce [`Client/`](Client/).

## Vlastní relay (self-hosting)

Ve výchozím stavu appka používá zabudovaný relay přes Tor, takže žádný server
nepotřebuješ. Relay (`server.py`) je ale záměrně drobný — čistá standardní knihovna
Pythonu 3, žádné závislosti — a běží na čemkoli: jednodeskovém počítači, starším PC,
VPS. V appce ho nastavíš v **Server chatu → Vlastní**.

Kompletní zprovoznění, HTTP API, konfiguraci Tor onion service i celý rozbor „co
server vidí" najdeš v **[SERVER.md](SERVER.md)**.

## Open source a licence

CryptoChat je svobodný open-source software licencovaný pod
**[GNU Affero General Public License v3.0](LICENSE)** (AGPL-3.0). Smíš ho
používat, studovat, sdílet i upravovat; pokud provozuješ upravenou verzi jako
síťovou službu (třeba upravený relay), musíš svým uživatelům nabídnout její
zdrojový kód. Úplné znění je v souboru [LICENSE](LICENSE).

Copyright © 2026 JelenXP.

## Poznámka o soukromí

**Žádné účty, žádná telefonní čísla ani e-maily** — nic nesváže tvou identitu s
konverzací. Když bys radši neprovozoval vůbec žádný server, existuje sourozenecký
projekt [**CryptoChatOffline**](https://github.com/JelenXP/CryptoChatOffline):
plně offline appka, která šifruje text a soubory do copy-paste blobků, jež pošleš
libovolným kanálem. Žije ve vlastním repozitáři a není součástí tohoto.
