# CryptoChat Relay — „slepá schránka"

Zero-knowledge relay pro CryptoChat, který běží na **serveru dle tvého výběru**
(stačí cokoli s Pythonem 3 — jednodeskový počítač, starší PC, VPS…). Je to
záměrně **hloupá schránka** (dead-drop): jen ukládá a vydává zašifrované blobky.

> **Dvě samostatné appky.** Původní **CryptoChat** (kořen repozitáře) zůstává čistě
> offline — zprávy se přenášejí ručně přes copy/paste/QR, bez jakéhokoli serveru.
> Tenhle relay obsluhuje druhou appku, **CryptoChatServer** (ve složce `Client/`),
> což je plnohodnotný messenger běžící na pozadí — a to tak, aby server **nevěděl
> obsah zpráv ani kdo komu píše**.

## Co server ví a neví

| | Vidí server? | Jak to zajišťujeme |
|---|---|---|
| Obsah zprávy | ❌ nikdy | E2E šifrování (AES-256-GCM) proběhne v telefonu |
| Kdo je odesílatel / příjemce | ❌ nikdy | Žádné účty. Jen anonymní ID schránky |
| Že „A píše B" | ❌ nikdy | ID schránky = HKDF ze sdíleného klíče, který server nikdy neviděl; navíc se rotuje |
| Délka zprávy | ❌ (skryjeme) | Klient blobky paddinguje na fixní velikosti |
| IP adresa klienta | ⚠️ jinak ano | Skrýt přes **Tor onion service** (viz níže) |
| Že *nějaká* schránka dostala blob | ✅ ano | Skrýt úplně nejde — viz „Threat model" |

Server drží vše **jen v paměti** (nic na disk), nevede **žádné access logy** a
schránky mají krátkou životnost (TTL) + mažou se po prvním vyzvednutí.

## Spuštění

Žádné závislosti — stačí **Python 3** (na většině Linuxů je předinstalovaný).

```bash
python3 server.py
```

Server naslouchá na `127.0.0.1:8787`. Konfigurace přes proměnné prostředí:

| Proměnná | Výchozí | Význam |
|---|---|---|
| `CC_HOST` | `127.0.0.1` | Naslouchací adresa (nech na localhost, ven přes Tor) |
| `CC_PORT` | `8787` | Port |
| `CC_MAX_BLOB_SIZE` | `2097152` (2 MB) | Max velikost jednoho blobu (fotka / kousek souboru) |
| `CC_MAX_MAILBOX_BLOBS` | `200` | Max blobků čekajících v jedné schránce |
| `CC_TTL_SECONDS` | `86400` (24 h) | Za jak dlouho nevyzvednutá schránka expiruje |
| `CC_MAX_TOTAL_BYTES` | `536870912` (512 MB) | Globální strop paměti |
| `CC_LONGPOLL_MAX` | `25` | Strop long-pollu (kolik sekund smí GET čekat na zprávu) |

Trvalý běh na serveru (Linux): použij přiloženou službu **`cryptochat-relay.service`** (systemd).

### Rychlý test

Ve druhém terminálu (server musí běžet):

```bash
python3 test_client.py
```

Ověří, že PUT/GET, ramování více blobků, mazání po vyzvednutí a limity fungují.

## API (kontrakt pro klienta)

Dvě cesty, `<id>` je 16–128 znaků z `A–Z a–z 0–9 _ -`:

- **`PUT /m/<id>`** — tělo = syrové bajty blobu → `204`. (Alias: `POST`.)
  Blob se přidá do fronty schránky.
  Chyby: `413` (chybí/moc velký), `409` (schránka plná), `507` (paměť plná).
- **`GET /m/<id>`** — vrátí **všechny** čekající blobky a schránku **vyprázdní**.
  - `200` + tělo délkově rámované: pro každý blob `[4 B big-endian délka][data]`,
    zřetězeně. Klient čte 4 bajty délky, pak tolik bajtů, dokud stream neskončí
    (stejný styl rámování, jaký appka používá u šifrování souborů).
  - `204` když je schránka prázdná.
  - **Long-polling:** s `?wait=<sekundy>` (strop `CC_LONGPOLL_MAX`) server drží
    spojení otevřené, dokud nedorazí zpráva — pak odpoví hned. Díky tomu chodí
    zprávy skoro okamžitě a přes Tor jde výrazně míň spojení než při pollování.
- **`GET /health`** — `200 ok` (kontrola dostupnosti).

> **Delivery je best-effort:** vyzvednutím se blob smaže. Když se odpověď ztratí,
> zpráva je pryč — klient to řeší tím, že další zprávy jdou na další (ratchet)
> schránky a v případě potřeby se pošlou znovu.

## Jak klient počítá ID schránky (běží v telefonu, ne tady)

Server o tomhle nic neví — pro něj je ID jen náhodný řetězec. Klient ho odvodí ze
sdíleného klíče kontaktu (ten samý HKDF princip, jaký už appka používá u SAS):

```
mailbox_A→B = base64url( HKDF(sharedKey, info = "mailbox" | směr | epocha) )
```

- **směr** odlišuje A→B a B→A (dvě schránky na jeden kontakt).
- **epocha** (číslo dne nebo krokový čítač) → ID se **rotuje**, takže server
  nespojí, že dnešní a zítřejší schránka patří téže dvojici.
- Blob je zašifrovaný **AES-256-GCM**, jehož autentizační tag zároveň slouží jako
  MAC → příjemce pozná a zahodí podvrhy (server je ověřit nemůže — klíč nemá, a to
  je správně).

**Online párování** (aby se dva našli bez prozrazení identity) používá stejný
trik s jednorázovou **pozvánkou**: `rendezvous = HKDF(pozvánka, "rendezvous")`.
A pošle svůj ML-KEM veřejný klíč do rendezvous schránky, B odpoví encapsulací →
oba mají sdílený klíč. Server viděl jen dvě náhodná ID. MITM kryje SAS kód.

## Skrytí IP: Tor onion service (doporučeno)

Aby server (ani ty jako jeho provozovatel) neviděl IP adresy klientů — a aby ses
vyhnul otevírání portů na routeru — vystav relay jako **Tor onion service**:

```bash
sudo apt install tor
```

Do `/etc/tor/torrc` přidej:

```
HiddenServiceDir /var/lib/tor/cryptochat/
HiddenServicePort 80 127.0.0.1:8787
```

```bash
sudo systemctl restart tor
sudo cat /var/lib/tor/cryptochat/hostname   # tvoje .onion adresa
```

Tu `.onion` adresu pak zadáš v appce (**Server chatu → Vlastní**). Klient má **Tor
zabudovaný přímo v sobě** (kmp-tor), takže koncový uživatel nemusí instalovat Orbot
ani nic dalšího. Výhody: skryje IP obou stran, funguje **bez port forwardingu**,
server je dostupný jen přes onion.

> ⚠️ **Běží-li server na notebooku:** zavření víka ho uspí, Tor přestane publikovat
> deskriptor skryté služby a onion se stane nedosažitelným (klient hlásí SOCKS kód 4).
> Zakaž proto uspávání:
> ```bash
> sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
> ```
> a v `/etc/systemd/logind.conf` nastav `HandleLidSwitch=ignore` (+ `…ExternalPower`,
> `…Docked`) a restartuj `systemd-logind`. Po nechtěném uspání pomůže
> `sudo systemctl restart tor`.

## Threat model — čeho to NEochrání (na rovinu)

- **Že *nějaká* schránka dostala blob v čase T** server ví (jinak by nedoručil).
  Skrýt „že se vůbec komunikuje" by chtělo *cover traffic* (falešné zprávy) —
  pokročilé, zatím neřešíme.
- **Globální pasivní odposlech** (vidí síť obou stran zároveň) může zkoušet
  časovou korelaci. Tor to ztěžuje, ne však dokonale.
- **Kompromitovaný telefon** = klíče jsou tam; to řeší app-lock/Keystore v appce,
  ne relay.
