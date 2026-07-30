# CryptoChat Relay

**English** · [Čeština](#cryptochat-relay-česky)

Operator / self-hoster reference for the CryptoChat relay (`server.py`). If you
just want to use the app, you don't need any of this — see
[README.md](README.md). This document is for people who want to run **their own**
relay and understand exactly what it does and doesn't do.

> **Repository layout.** This repository contains **two things**: the Android
> chat app (in [`Client/`](Client/)) and this relay server (`server.py`). The
> fully offline sibling app — the one that encrypts into copy-paste blobs with no
> server at all — is a **separate** repository,
> [CryptoChatOffline](https://github.com/JelenXP/CryptoChatOffline), and is **not**
> part of this repo. A user-facing overview of the whole project lives in
> [README.md](README.md).

## What the relay is

A **zero-knowledge dead-drop**: a deliberately dumb mailbox that only stores and
hands back opaque encrypted blobs. It has no accounts, no address book, no idea
who is talking to whom, and no persistence — everything lives in RAM and expires.
It is a single file (`server.py`) with **no dependencies** beyond the Python 3
standard library.

The client computes an anonymous mailbox ID from a shared key the server never
saw (`HKDF`), rotates it daily, and encrypts every blob end-to-end (AES-256-GCM)
before it ever reaches the relay. The server is a pipe; it can't read, can't
attribute, and can't link conversations across days.

### What the server sees — and doesn't

| | Sees it? | How that's ensured |
|---|---|---|
| Message / photo / file content | **No, never** | End-to-end AES-256-GCM happens on the phone; the relay only ever holds ciphertext |
| Who is the sender / recipient | **No, never** | No accounts, no login — only opaque mailbox IDs |
| That "A is talking to B" | **No, never** | Mailbox ID = `HKDF(sharedKey, dir\|epoch)`; the server never saw the key, and the ID rotates daily |
| Message length | **No (hidden)** | The client pads text blobs into fixed size buckets |
| Client IP address | **Hidden via Tor** | Reached as a Tor onion service; all connections arrive from `127.0.0.1` |
| That *some* mailbox received *something* | **Yes** | Can't be fully hidden — see the [threat model](#threat-model--what-the-relay-does-not-hide) |

Everything is kept **in memory only** (nothing on disk except optional error
reports), there are **no access logs**, mailboxes have a short TTL, and blobs are
deleted the moment they're acknowledged. Seize the server and you find at most a
handful of encrypted blobs that vanish within the TTL.

## Quick start

You need only **Python 3** (preinstalled on most Linux distributions). No `pip`,
no virtualenv, no dependencies.

```bash
python3 server.py
```

The relay listens on `127.0.0.1:8787` and prints its effective limits at startup.
Configuration is entirely via `CC_*` environment variables (full table
[below](#configuration-reference)); every value has a sane default.

```bash
python3 server.py --help      # usage summary
python3 server.py --setup     # interactive configuration wizard (see next section)
```

## Guided setup (`--setup`)

`python3 server.py --setup` is an interactive wizard that **does not touch the
running server** — it only asks a handful of questions, writes a `relay.env` file
with the matching `CC_*` variables, and prints how to launch the relay with that
config (plus optional Tor and systemd snippets). Skipping the wizard and running
plain `python3 server.py` still works — every setting has a default.

The wizard walks through:

1. **Listen address** — `[1]` localhost `127.0.0.1` (recommended; the relay is
   meant to sit behind Tor) or `[2]` `0.0.0.0` to expose it directly to the
   network (only if you know why — without Tor your IP is visible). → `CC_HOST`
2. **Port** — TCP port to listen on (default `8787`). → `CC_PORT`
3. **Message TTL in hours** — how long an unclaimed mailbox survives (default
   24 h). → `CC_TTL_SECONDS` (written as seconds)
4. **Memory cap in MB** — the global ceiling on data held at once (default 512).
   → `CC_MAX_TOTAL_BYTES`
5. **Max blob size in MB** — largest single blob (default 2). The wizard warns:
   **do not lower below 2**, because the app sends photos and file chunks up to
   ~2 MB and a smaller cap would silently break their delivery. → `CC_MAX_BLOB_SIZE`
6. **Tor onion service** (optional) — offers to print step-by-step `torrc`
   instructions (see [Tor onion service](#tor-onion-service) below) so clients
   reach the relay without exposing an IP or opening a router port.
7. **systemd service** (optional) — offers to print a ready-to-paste unit file
   with `EnvironmentFile=/opt/cryptochat-relay/relay.env` so the relay runs in the
   background and survives reboots.

It then writes `./relay.env`, for example:

```
CC_HOST=127.0.0.1
CC_PORT=8787
CC_TTL_SECONDS=86400
CC_MAX_TOTAL_BYTES=536870912
CC_MAX_BLOB_SIZE=2097152
```

and tells you how to start the relay with it:

```bash
set -a; . ./relay.env; python3 server.py      # load the file, then run
```

The same `relay.env` is what a systemd unit reads via `EnvironmentFile=` (see
[systemd](#running-under-systemd)). You can of course also set any variable
inline: `CC_TTL_SECONDS=3600 python3 server.py`.

## Configuration reference

All configuration is read once at startup from `CC_*` environment variables.
Byte-size defaults are shown human-readable; the raw value is in parentheses.

### Core & storage

| Variable | Default | Meaning |
|---|---|---|
| `CC_HOST` | `127.0.0.1` | Listen address. Localhost by default — the relay is meant to run **behind** a Tor onion service (Tor connects locally). Set `0.0.0.0` to expose it directly to the network (not recommended: reveals your IP). |
| `CC_PORT` | `8787` | TCP port to listen on. |
| `CC_MAX_BLOB_SIZE` | 2 MB (`2097152`) | Max size of a single blob. Text is tiny, but photos and file chunks need up to ~2 MB. **Do not lower below 2 MB** or the app can't deliver media. |
| `CC_MAX_MAILBOX_BLOBS` | `200` | Max blobs queued in one mailbox (e.g. sender outruns a slow recipient). A `PUT` past this returns `409`. |
| `CC_MAX_BOXES` | `50000` | Cap on the **number** of mailboxes. `CC_MAX_TOTAL_BYTES` counts only blob bytes, but each mailbox carries ~1 kB of Python overhead; without this cap an attacker who knows the onion address could inflate box count with 1-byte blobs on thousands of IDs and OOM the process while the byte counter still reads kilobytes. Over the cap, oldest mailboxes are evicted. |
| `CC_TTL_SECONDS` | 24 h (`86400`) | How long an unclaimed mailbox lives. Each write extends the mailbox's lifetime. |
| `CC_MAX_TOTAL_BYTES` | 512 MB (`536870912`) | Global memory ceiling. When exceeded, the relay evicts mailboxes (expired first, then oldest live) to make room; if it still can't fit, `PUT` returns `507`. |

### Delivery & long-polling

| Variable | Default | Meaning |
|---|---|---|
| `CC_LONGPOLL_MAX` | `90` s | Upper bound on how long a `GET` may block on `?wait=`. Longer waits mean fewer client wake-ups (battery) without delaying delivery — a `PUT` wakes a waiting `GET` instantly. |
| `CC_MAX_PEEK_BYTES` | 8 MB (`8388608`) | Max bytes returned in a single `GET` response. A mailbox may legitimately hold far more (`MAX_MAILBOX_BLOBS × MAX_BLOB_SIZE`); the rest is returned in the next round. |
| `CC_MAX_CONNECTIONS` | `512` | Cap on concurrent connections (one thread each). Over the cap, new connections are closed immediately rather than driving the box into swap or `can't start new thread`. |
| `CC_MAX_LONGPOLL` | `CC_MAX_CONNECTIONS ÷ 2` (`256`) | Separate, smaller cap on concurrent **blocking** long-polls, so a single client can't occupy every connection slot with `?wait=` GETs. Over it, a long-poll degrades to a non-blocking read (returns immediately, client retries). |

### Abuse / DoS guards

| Variable | Default | Meaning |
|---|---|---|
| `CC_DRAIN_CAP` | 8 MB (`8388608`) | How much of an oversized request body to "drain" so the client can still read the `413`. Bodies larger than this are disconnected outright. |
| `CC_RATE_LIMIT_REQUESTS` | `3000` | Requests per window per client key. Behind Tor **every** client shares one key (`127.0.0.1`), so this is a loose safety net for direct exposure — the real defense is `CC_MAX_CONNECTIONS`. |
| `CC_RATE_LIMIT_WINDOW` | `60` s | Sliding window for the rate limit. |
| `CC_HEADER_TIMEOUT` | `20` s | Total deadline for reading the request line + headers, and for an idle keep-alive connection between requests. Short — a slowloris / idle client is cut off almost immediately. |
| `CC_IO_TIMEOUT` | `60` s | Per-recv / per-send timeout for the **active** transfer (body read, response write). Longer than the header timeout because a 2 MB blob can trickle over a slow Tor circuit. |
| `CC_BODY_TIMEOUT` | `120` s | Overall deadline to read the **whole** request body (slow-POST defense: a big `Content-Length` dripped byte-by-byte would otherwise reset the per-recv timeout forever). |
| `CC_MAX_KEEPALIVE_REQUESTS` | `10000` | Requests allowed on one keep-alive connection before the server sends `Connection: close` and the client reconnects (cheap — a new TCP stream over the already-warm Tor circuit). |
| `CC_MAX_HEADER_BYTES` | 64 KB (`65536`) | Cap on total request line + header size. Legitimate requests are a few hundred bytes. |

### Error reports (the only thing written to disk)

| Variable | Default | Meaning |
|---|---|---|
| `CC_REPORTS_DIR` | *auto* | Directory for `POST /report`. If unset, the relay picks the first **writable** of: `/var/lib/cryptochat-relay/reports`, `~/cryptochat-reports`, `<tmpdir>/cryptochat-reports`. If none is writable, `/report` is disabled. |
| `CC_MAX_REPORT_SIZE` | 256 KB (`262144`) | Max size of a single report body. |
| `CC_MAX_REPORTS` | `500` | Max number of report folders kept on disk (oldest deleted first). |
| `CC_MAX_REPORTS_BYTES` | 64 MB (`67108864`) | Max total size of stored reports on disk. |

## HTTP API

The client contract is tiny. `<id>` is an opaque mailbox ID matching
`^[A-Za-z0-9_-]{16,128}$` (URL-safe base64 / hex, 16–128 chars); the server treats
it as a meaningless string. Any request that fails the rate limit gets `429`; a
malformed path or ID gets `404`.

### `PUT /m/<id>` — store a blob (alias: `POST /m/<id>`)

Body = the raw blob bytes; `Content-Length` is required. The blob is appended to
the mailbox queue.

| Status | Meaning |
|---|---|
| `204` | Stored. |
| `411` | Missing / zero / non-numeric `Content-Length`. |
| `413` | Body larger than `CC_MAX_BLOB_SIZE` (up to `CC_DRAIN_CAP` is drained so the client can read this; larger → connection closed). |
| `400` | Short body — the client promised `Content-Length` but sent fewer bytes (a truncated blob is rejected, never stored). |
| `409` | Mailbox full (`CC_MAX_MAILBOX_BLOBS` reached). |
| `507` | Server storage full (`CC_MAX_TOTAL_BYTES`, even after eviction). |

### `GET /m/<id>` — fetch blobs

Two behaviours, plus optional long-poll:

- **Default (destructive).** Returns all queued blobs that fit under
  `CC_MAX_PEEK_BYTES` and **deletes exactly those** (the rest stay for the next
  round). `200` with the body **length-framed**: for each blob `[4-byte
  big-endian length][data]`, concatenated; the client reads 4 bytes, then that
  many bytes, until the stream ends. `204` if the mailbox is empty.
- **Acknowledged read (`?ack=1`).** Returns the same length-framed body but does
  **not** delete anything, and adds a header `X-CC-Seq: <n>` = the sequence number
  of the last blob returned. The client stores the messages, then confirms with
  `DELETE` (below). This is the reliable path: if the connection drops mid-response
  (a broken Tor circuit, a switched network), nothing is lost because the blobs are
  still on the server. The client filters any re-delivery by fingerprint.
- **Long-poll (`?wait=<s>`).** Clamped to `[0, CC_LONGPOLL_MAX]`. The server holds
  the connection open until a blob arrives, then answers immediately — far fewer
  round-trips over Tor than polling. If the blocking long-poll pool
  (`CC_MAX_LONGPOLL`) is exhausted, the wait degrades to `0` (immediate empty
  reply). Combines with either read mode.

### `DELETE /m/<id>?upto=<seq>` — acknowledge receipt

Deletes blobs with sequence number ≤ `upto` (the value the client got from
`X-CC-Seq`). Used after an `?ack=1` read once the messages are safely stored.
`204` on success; `400` if `upto` is missing or negative.

### `GET /health` — liveness

`200 ok`. No side effects.

### `POST /report` — voluntary anonymous error report

The **only** endpoint that writes to disk. The user sends this deliberately from
the app's "report a problem" screen and sees the exact payload beforehand; it is
already anonymous at the client (no messages, keys, contact names or mailbox IDs)
and arrives over Tor. Each report is stored verbatim to
`<reports-dir>/<timestamp>-<random-suffix>/report.json` — the server never parses
or augments it, and stores nothing about the sender (behind Tor there's nothing to
store; every connection is `127.0.0.1`). The random suffix avoids same-second
collisions and hides the count / order of reports.

| Status | Meaning |
|---|---|
| `204` | Stored. |
| `411` | Missing / zero `Content-Length`. |
| `413` | Body larger than `CC_MAX_REPORT_SIZE`. |
| `400` | Short body. |
| `500` | No writable reports directory. |

> **Delivery is best-effort.** In the default (destructive) read a picked-up blob
> is gone; if the response is lost, so is the message. The client mitigates this
> by using `?ack=1` + `DELETE` for reliable delivery, by moving subsequent messages
> onto rotating (ratchet) mailboxes, and by re-sending when needed.

## Tor onion service

Exposing the relay as a **Tor onion service** hides the IP of both the server and
every client, and works **without any port-forwarding** on your router — the relay
never needs to be reachable from the public internet. The client has Tor **built
in** (kmp-tor), so end users install nothing extra.

Install Tor, then add to `/etc/tor/torrc`:

```
HiddenServiceDir /var/lib/tor/cryptochat/
HiddenServicePort 80 127.0.0.1:8787
```

Restart Tor and read the generated hostname:

```bash
sudo systemctl restart tor
sudo cat /var/lib/tor/cryptochat/hostname     # your .onion address
```

Put that `.onion` address into the app under **Chat server → Custom** as
`http://<address>`.

The bundled **`setup-server.sh`** automates exactly this (install Tor → deploy the
relay as a systemd service on `127.0.0.1:8787` → enable the onion service → print
the `.onion`); run it once with `sudo bash setup-server.sh`. The `--setup` wizard
prints the same `torrc` snippet if you prefer to do it by hand.

> **If the relay runs on a laptop:** closing the lid suspends it, Tor stops
> publishing the hidden-service descriptor, and the onion becomes unreachable (the
> client reports **SOCKS code 4**). Disable sleep:
> ```bash
> sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
> ```
> and set `HandleLidSwitch=ignore` (plus `…ExternalPower`, `…Docked`) in
> `/etc/systemd/logind.conf`, then restart `systemd-logind`. After an accidental
> suspend, `sudo systemctl restart tor` gets the onion back.

## Running under systemd

The bundled **`cryptochat-relay.service`** runs the relay in the background and
restarts it on failure / reboot. Copy the repo to the server (e.g.
`/opt/cryptochat-relay`), adjust the user/paths, then:

```bash
sudo cp cryptochat-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cryptochat-relay
systemctl status cryptochat-relay
```

It runs as an unprivileged `cryptochat` user, sets config via `Environment=`
lines, and hardens the service (`NoNewPrivileges`, `ProtectSystem=strict`,
`ProtectHome`, `PrivateTmp`, `ReadOnlyPaths=/opt/cryptochat-relay`) — safe because
the relay writes nothing to disk in normal operation.

The `--setup` wizard prints an equivalent unit that reads its config from a file
instead:

```ini
[Service]
User=cryptochat
WorkingDirectory=/opt/cryptochat-relay
EnvironmentFile=/opt/cryptochat-relay/relay.env
ExecStart=/usr/bin/python3 /opt/cryptochat-relay/server.py
Restart=on-failure
```

Point `EnvironmentFile=` at the `relay.env` the wizard generated and all your
`CC_*` settings load automatically.

> **Note on reports + hardening.** With the hardened unit,
> `ProtectSystem=strict` / `ProtectHome=true` block the default report
> directories, so `POST /report` would fall back to the service's private `/tmp`
> (which is wiped on restart). If you want persistent reports, set an explicit
> `CC_REPORTS_DIR` and add a matching `ReadWritePaths=` to the unit.

## Threat model — what the relay does *not* hide

- **That *some* mailbox received *something* at time T** is knowable to the server
  (otherwise it couldn't deliver). Hiding that communication happens at all would
  need *cover traffic* (decoy messages) — not implemented.
- **A global passive observer** who can watch both sides of the network at once can
  attempt timing correlation. Tor makes this harder, not impossible.
- **A compromised phone** has the keys on it; that's what the app lock and Android
  Keystore defend, not the relay.
- If the server is seized while running, it holds only a handful of ephemeral,
  end-to-end-encrypted blobs (no logs, no history, no metadata about who they're
  for) that expire within the TTL.

---
---

# CryptoChat Relay (česky)

[English](#cryptochat-relay) · **Čeština**

Referenční příručka pro provozovatele / self-hostery relaye CryptoChat
(`server.py`). Pokud chceš appku jen používat, nic z tohohle nepotřebuješ — viz
[README.md](README.md). Tenhle dokument je pro toho, kdo si chce postavit
**vlastní** relay a rozumět přesně tomu, co dělá a co nedělá.

> **Uspořádání repozitáře.** Tenhle repozitář obsahuje **dvě věci**: Android
> chatovou appku (ve složce [`Client/`](Client/)) a tento relay server
> (`server.py`). Plně offline sourozenecká appka — ta, co šifruje do copy-paste
> blobků úplně bez serveru — je **samostatný** repozitář,
> [CryptoChatOffline](https://github.com/JelenXP/CryptoChatOffline), a **není**
> součástí tohohle. Uživatelský přehled celého projektu je v
> [README.md](README.md).

## Co relay je

**Zero-knowledge slepá schránka** (dead-drop): záměrně hloupá schránka, která umí
jen ukládat a vydávat neprůhledné zašifrované blobky. Nemá účty, adresář, netuší,
kdo komu píše, a nic si nepamatuje — vše žije v RAM a expiruje. Je to jediný soubor
(`server.py`) **bez jakýchkoli závislostí** kromě standardní knihovny Pythonu 3.

Klient si spočítá anonymní ID schránky ze sdíleného klíče, který server nikdy
neviděl (`HKDF`), denně ho rotuje a každý blob end-to-end zašifruje (AES-256-GCM),
ještě než se k relayi vůbec dostane. Server je jen roura; nedokáže číst,
přiřazovat, ani spojovat konverzace napříč dny.

### Co server vidí a co ne

| | Vidí to? | Jak to zajišťujeme |
|---|---|---|
| Obsah zprávy / fotky / souboru | **Ne, nikdy** | End-to-end AES-256-GCM proběhne v telefonu; relay drží vždy jen šifrotext |
| Kdo je odesílatel / příjemce | **Ne, nikdy** | Žádné účty, žádné přihlášení — jen neprůhledná ID schránek |
| Že „A píše B" | **Ne, nikdy** | ID schránky = `HKDF(sdílenýKlíč, směr\|epocha)`; server klíč nikdy neviděl a ID se denně rotuje |
| Délka zprávy | **Ne (skryjeme)** | Klient textové blobky paddinguje do fixních košů |
| IP adresa klienta | **Skrytá přes Tor** | Dostupné jako Tor onion service; všechna spojení chodí z `127.0.0.1` |
| Že *nějaká* schránka *něco* dostala | **Ano** | Skrýt úplně nejde — viz [threat model](#threat-model--čeho-to-neochrání) |

Vše se drží **jen v paměti** (nic na disk kromě volitelných hlášení chyb), nejsou
**žádné access logy**, schránky mají krátké TTL a blobky se mažou hned po potvrzení.
I kdyby někdo server zabavil, najde nanejvýš pár šifrovaných blobků, které do TTL
zmizí.

## Rychlý start

Stačí **Python 3** (na většině Linuxů předinstalovaný). Žádný `pip`, žádný
virtualenv, žádné závislosti.

```bash
python3 server.py
```

Relay naslouchá na `127.0.0.1:8787` a při startu vypíše své efektivní limity.
Konfigurace je výhradně přes proměnné prostředí `CC_*` (plná tabulka
[níže](#referenční-tabulka-konfigurace)); každá hodnota má rozumný výchozí stav.

```bash
python3 server.py --help      # shrnutí použití
python3 server.py --setup     # interaktivní průvodce konfigurací (viz další sekce)
```

## Průvodce nastavením (`--setup`)

`python3 server.py --setup` je interaktivní průvodce, který **nesahá na běžící
server** — jen se zeptá na pár věcí, zapíše soubor `relay.env` s odpovídajícími
proměnnými `CC_*` a vypíše, jak s tou konfigurací relay spustit (plus volitelné
úryvky pro Tor a systemd). Průvodce lze přeskočit a spustit prosté
`python3 server.py` — každé nastavení má výchozí hodnotu.

Průvodce projde:

1. **Naslouchací adresa** — `[1]` localhost `127.0.0.1` (doporučeno; relay má sedět
   za Torem) nebo `[2]` `0.0.0.0` pro přímé vystavení do sítě (jen když víš proč —
   bez Toru je tvoje IP viditelná). → `CC_HOST`
2. **Port** — TCP port pro naslouchání (výchozí `8787`). → `CC_PORT`
3. **TTL zprávy v hodinách** — jak dlouho přežije nevyzvednutá schránka (výchozí
   24 h). → `CC_TTL_SECONDS` (zapíše se v sekundách)
4. **Strop paměti v MB** — globální strop na současně držená data (výchozí 512).
   → `CC_MAX_TOTAL_BYTES`
5. **Max velikost blobu v MB** — největší jeden blob (výchozí 2). Průvodce varuje:
   **nesnižuj pod 2**, protože appka posílá fotky a kousky souborů až do ~2 MB a
   menší strop by tiše rozbil jejich doručení. → `CC_MAX_BLOB_SIZE`
6. **Tor onion service** (volitelně) — nabídne vypsání krok-za-krokem `torrc`
   návodu (viz [Tor onion service](#tor-onion-service-1) níže), aby se klienti k
   relayi dostali bez prozrazení IP a bez otevírání portu na routeru.
7. **systemd služba** (volitelně) — nabídne vypsání hotového unit souboru s
   `EnvironmentFile=/opt/cryptochat-relay/relay.env`, aby relay běžel na pozadí a
   přežil restart.

Nakonec zapíše `./relay.env`, například:

```
CC_HOST=127.0.0.1
CC_PORT=8787
CC_TTL_SECONDS=86400
CC_MAX_TOTAL_BYTES=536870912
CC_MAX_BLOB_SIZE=2097152
```

a poradí, jak s ním relay spustit:

```bash
set -a; . ./relay.env; python3 server.py      # načti soubor a spusť
```

Tentýž `relay.env` čte i systemd unit přes `EnvironmentFile=` (viz
[systemd](#spuštění-přes-systemd)). Samozřejmě jde jakoukoli proměnnou nastavit i
inline: `CC_TTL_SECONDS=3600 python3 server.py`.

## Referenční tabulka konfigurace

Veškerá konfigurace se čte jednou při startu z proměnných prostředí `CC_*`.
Bajtové výchozí hodnoty jsou uvedené čitelně; syrová hodnota je v závorce.

### Jádro a úložiště

| Proměnná | Výchozí | Význam |
|---|---|---|
| `CC_HOST` | `127.0.0.1` | Naslouchací adresa. Výchozí localhost — relay má běžet **za** Tor onion service (Tor se připojuje lokálně). Nastav `0.0.0.0` pro přímé vystavení do sítě (nedoporučeno: prozradí tvou IP). |
| `CC_PORT` | `8787` | TCP port pro naslouchání. |
| `CC_MAX_BLOB_SIZE` | 2 MB (`2097152`) | Max velikost jednoho blobu. Text je drobný, ale fotky a kousky souborů potřebují až ~2 MB. **Nesnižuj pod 2 MB**, jinak appka nedoručí média. |
| `CC_MAX_MAILBOX_BLOBS` | `200` | Max blobků ve frontě jedné schránky (např. odesílatel předběhne pomalého příjemce). `PUT` přes tuhle mez vrátí `409`. |
| `CC_MAX_BOXES` | `50000` | Strop na **počet** schránek. `CC_MAX_TOTAL_BYTES` sčítá jen bajty blobů, ale každá schránka nese ~1 kB Python režie; bez tohoto stropu by útočník znající onion adresu nafoukl počet schránek 1bajtovými blobky na tisíce ID a shodil proces na OOM, zatímco bajtové počítadlo hlásí kilobajty. Nad strop se obětují nejstarší schránky. |
| `CC_TTL_SECONDS` | 24 h (`86400`) | Jak dlouho žije nevyzvednutá schránka. Každý zápis její životnost prodlouží. |
| `CC_MAX_TOTAL_BYTES` | 512 MB (`536870912`) | Globální strop paměti. Při překročení relay obětuje schránky (nejdřív expirované, pak nejstarší živé), aby udělal místo; když se ani tak nevejde, `PUT` vrátí `507`. |

### Doručování a long-polling

| Proměnná | Výchozí | Význam |
|---|---|---|
| `CC_LONGPOLL_MAX` | `90` s | Strop na to, jak dlouho smí `GET` blokovat na `?wait=`. Delší čekání = méně probuzení klienta (baterie) bez zdržení doručení — `PUT` čekající `GET` probudí okamžitě. |
| `CC_MAX_PEEK_BYTES` | 8 MB (`8388608`) | Max bajtů vrácených v jedné `GET` odpovědi. Schránka může legitimně držet mnohem víc (`MAX_MAILBOX_BLOBS × MAX_BLOB_SIZE`); zbytek se vrátí v dalším kole. |
| `CC_MAX_CONNECTIONS` | `512` | Strop souběžných spojení (každé = jedno vlákno). Nad strop se nová spojení rovnou zavřou, místo aby server upadl do swapu / spadl na `can't start new thread`. |
| `CC_MAX_LONGPOLL` | `CC_MAX_CONNECTIONS ÷ 2` (`256`) | Samostatný, menší strop na souběžné **blokující** long-polly, aby jediný klient neobsadil `?wait=` GETy všechny sloty spojení. Nad strop se long-poll degraduje na neblokující čtení (vrátí hned, klient to zkusí znovu). |

### Obrana proti zneužití / DoS

| Proměnná | Výchozí | Význam |
|---|---|---|
| `CC_DRAIN_CAP` | 8 MB (`8388608`) | Kolik přerostlého těla „vypít", aby klient stihl přečíst `413`. Těla větší než tohle se rovnou odpojí. |
| `CC_RATE_LIMIT_REQUESTS` | `3000` | Počet požadavků za okno na klienta. Za Torem sdílí **všichni** klienti jeden klíč (`127.0.0.1`), takže je to volná pojistka pro přímé vystavení — skutečnou obranou je `CC_MAX_CONNECTIONS`. |
| `CC_RATE_LIMIT_WINDOW` | `60` s | Klouzavé okno rate limitu. |
| `CC_HEADER_TIMEOUT` | `20` s | Celkový deadline na čtení request line + hlaviček a na nečinné keep-alive spojení mezi požadavky. Krátký — slowloris / nečinný klient se utne skoro hned. |
| `CC_IO_TIMEOUT` | `60` s | Per-recv / per-send timeout pro **aktivní** přenos (čtení těla, zápis odpovědi). Delší než hlavičkový, protože 2 MB blob může přes pomalý Tor okruh téct dlouho. |
| `CC_BODY_TIMEOUT` | `120` s | Celkový deadline na načtení **celého** těla požadavku (obrana proti slow-POST: velký `Content-Length` kapaný po bajtech by jinak resetoval per-recv timeout donekonečna). |
| `CC_MAX_KEEPALIVE_REQUESTS` | `10000` | Kolik požadavků smí projít jedním keep-alive spojením, než server pošle `Connection: close` a klient se znovu připojí (levné — nový TCP stream přes už zahřátý Tor okruh). |
| `CC_MAX_HEADER_BYTES` | 64 KB (`65536`) | Strop na celkovou velikost request line + hlaviček. Legitimní požadavky mají stovky bajtů. |

### Hlášení chyb (jediné, co jde na disk)

| Proměnná | Výchozí | Význam |
|---|---|---|
| `CC_REPORTS_DIR` | *auto* | Adresář pro `POST /report`. Když není nastaven, relay vezme první **zapisovatelný** z: `/var/lib/cryptochat-relay/reports`, `~/cryptochat-reports`, `<tmp>/cryptochat-reports`. Když není zapisovatelný žádný, `/report` je vypnutý. |
| `CC_MAX_REPORT_SIZE` | 256 KB (`262144`) | Max velikost těla jednoho hlášení. |
| `CC_MAX_REPORTS` | `500` | Max počet složek hlášení na disku (nejstarší se mažou první). |
| `CC_MAX_REPORTS_BYTES` | 64 MB (`67108864`) | Max celková velikost uložených hlášení na disku. |

## HTTP API

Kontrakt pro klienta je drobný. `<id>` je neprůhledné ID schránky odpovídající
`^[A-Za-z0-9_-]{16,128}$` (URL-safe base64 / hex, 16–128 znaků); server ho bere
jako bezvýznamný řetězec. Každý požadavek, který neprojde rate limitem, dostane
`429`; špatná cesta nebo ID dostane `404`.

### `PUT /m/<id>` — ulož blob (alias: `POST /m/<id>`)

Tělo = syrové bajty blobu; `Content-Length` je povinný. Blob se přidá do fronty
schránky.

| Kód | Význam |
|---|---|
| `204` | Uloženo. |
| `411` | Chybějící / nulový / nečíselný `Content-Length`. |
| `413` | Tělo větší než `CC_MAX_BLOB_SIZE` (až do `CC_DRAIN_CAP` se vypije, aby to klient přečetl; větší → spojení se zavře). |
| `400` | Krátké tělo — klient slíbil `Content-Length`, ale poslal míň bajtů (useknutý blob se odmítne, nikdy neuloží). |
| `409` | Schránka plná (dosaženo `CC_MAX_MAILBOX_BLOBS`). |
| `507` | Úložiště serveru plné (`CC_MAX_TOTAL_BYTES`, i po evikci). |

### `GET /m/<id>` — vyzvedni blobky

Dvě chování, plus volitelný long-poll:

- **Výchozí (destruktivní).** Vrátí všechny blobky ve frontě, které se vejdou pod
  `CC_MAX_PEEK_BYTES`, a **přesně ty smaže** (zbytek zůstane na příště). `200` s
  tělem **délkově rámovaným**: pro každý blob `[4B big-endian délka][data]`,
  zřetězeně; klient čte 4 bajty, pak tolik bajtů, dokud stream neskončí. `204` když
  je schránka prázdná.
- **Potvrzované čtení (`?ack=1`).** Vrátí totéž délkově rámované tělo, ale **nic
  nesmaže** a přidá hlavičku `X-CC-Seq: <n>` = pořadové číslo posledního vráceného
  blobu. Klient zprávy uloží a pak potvrdí přes `DELETE` (níže). Tohle je spolehlivá
  cesta: když spojení spadne uprostřed odpovědi (rozpadlý Tor okruh, přepnutá síť),
  nic se neztratí, protože blobky jsou pořád na serveru. Opakované doručení si klient
  odfiltruje podle otisku.
- **Long-poll (`?wait=<s>`).** Oříznuto na `[0, CC_LONGPOLL_MAX]`. Server drží
  spojení otevřené, dokud nedorazí blob, pak odpoví hned — výrazně méně round-tripů
  přes Tor než pollování. Když je pool blokujících long-pollů (`CC_MAX_LONGPOLL`)
  vyčerpaný, čekání degraduje na `0` (okamžitá prázdná odpověď). Kombinuje se s
  oběma režimy čtení.

### `DELETE /m/<id>?upto=<seq>` — potvrď příjem

Smaže blobky s pořadovým číslem ≤ `upto` (hodnota, kterou klient dostal z
`X-CC-Seq`). Používá se po čtení `?ack=1`, jakmile jsou zprávy bezpečně uložené.
`204` při úspěchu; `400` když `upto` chybí nebo je záporné.

### `GET /health` — kontrola dostupnosti

`200 ok`. Bez vedlejších efektů.

### `POST /report` — dobrovolné anonymní hlášení chyby

**Jediný** endpoint, který zapisuje na disk. Uživatel ho posílá záměrně z obrazovky
appky „nahlásit chybu" a předem vidí přesný obsah; už od klienta je anonymní (žádné
zprávy, klíče, jména kontaktů ani ID schránek) a chodí přes Tor. Každé hlášení se
uloží doslova do `<adresář-hlášení>/<timestamp>-<náhodný-suffix>/report.json` —
server ho nijak neparsuje ani nedoplňuje a neukládá nic o odesílateli (za Torem
stejně není co ukládat; každé spojení je `127.0.0.1`). Náhodný suffix brání kolizi
ve stejné sekundě a skrývá počet / pořadí hlášení.

| Kód | Význam |
|---|---|
| `204` | Uloženo. |
| `411` | Chybějící / nulový `Content-Length`. |
| `413` | Tělo větší než `CC_MAX_REPORT_SIZE`. |
| `400` | Krátké tělo. |
| `500` | Žádný zapisovatelný adresář hlášení. |

> **Doručení je best-effort.** Ve výchozím (destruktivním) čtení je vyzvednutý blob
> pryč; když se odpověď ztratí, ztratí se i zpráva. Klient to řeší režimem `?ack=1`
> + `DELETE` pro spolehlivé doručení, přesouváním dalších zpráv na rotující
> (ratchet) schránky a opětovným odesláním, když je potřeba.

## Tor onion service

Vystavení relaye jako **Tor onion service** skryje IP serveru i každého klienta a
funguje **bez jakéhokoli port-forwardingu** na routeru — relay nikdy nemusí být
dostupný z veřejného internetu. Klient má Tor **zabudovaný** (kmp-tor), takže
koncový uživatel nic dalšího neinstaluje.

Nainstaluj Tor a do `/etc/tor/torrc` přidej:

```
HiddenServiceDir /var/lib/tor/cryptochat/
HiddenServicePort 80 127.0.0.1:8787
```

Restartuj Tor a přečti si vygenerovaný hostname:

```bash
sudo systemctl restart tor
sudo cat /var/lib/tor/cryptochat/hostname     # tvoje .onion adresa
```

Tu `.onion` adresu zadej v appce pod **Server chatu → Vlastní** jako
`http://<adresa>`.

Přiložený **`setup-server.sh`** dělá přesně tohle automaticky (nainstaluje Tor →
nasadí relay jako systemd službu na `127.0.0.1:8787` → zapne onion službu → vypíše
`.onion`); spusť ho jednou přes `sudo bash setup-server.sh`. Průvodce `--setup`
vypíše tentýž `torrc` úryvek, pokud to chceš udělat ručně.

> **Když relay běží na notebooku:** zavření víka ho uspí, Tor přestane publikovat
> deskriptor skryté služby a onion se stane nedosažitelným (klient hlásí **SOCKS
> kód 4**). Zakaž uspávání:
> ```bash
> sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target
> ```
> a v `/etc/systemd/logind.conf` nastav `HandleLidSwitch=ignore` (+ `…ExternalPower`,
> `…Docked`) a restartuj `systemd-logind`. Po nechtěném uspání pomůže
> `sudo systemctl restart tor`.

## Spuštění přes systemd

Přiložený **`cryptochat-relay.service`** spustí relay na pozadí a restartuje ho při
selhání / restartu. Zkopíruj repo na server (např. `/opt/cryptochat-relay`), uprav
uživatele/cesty a pak:

```bash
sudo cp cryptochat-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cryptochat-relay
systemctl status cryptochat-relay
```

Běží pod neprivilegovaným uživatelem `cryptochat`, nastavuje konfiguraci řádky
`Environment=` a zpevňuje službu (`NoNewPrivileges`, `ProtectSystem=strict`,
`ProtectHome`, `PrivateTmp`, `ReadOnlyPaths=/opt/cryptochat-relay`) — bezpečné,
protože relay v běžném provozu na disk nic nezapisuje.

Průvodce `--setup` vypíše ekvivalentní unit, který si konfiguraci čte ze souboru:

```ini
[Service]
User=cryptochat
WorkingDirectory=/opt/cryptochat-relay
EnvironmentFile=/opt/cryptochat-relay/relay.env
ExecStart=/usr/bin/python3 /opt/cryptochat-relay/server.py
Restart=on-failure
```

Nasměruj `EnvironmentFile=` na `relay.env`, který průvodce vygeneroval, a všechna
tvoje nastavení `CC_*` se načtou automaticky.

> **Poznámka k hlášením + zpevnění.** Se zpevněným unitem `ProtectSystem=strict` /
> `ProtectHome=true` blokují výchozí adresáře hlášení, takže `POST /report` spadne
> zpátky na privátní `/tmp` služby (které se při restartu smaže). Když chceš
> trvalá hlášení, nastav explicitní `CC_REPORTS_DIR` a přidej k unitu odpovídající
> `ReadWritePaths=`.

## Threat model — čeho to *neochrání*

- **Že *nějaká* schránka v čase T *něco* dostala** server ví (jinak by nedoručil).
  Skrýt, že se vůbec komunikuje, by chtělo *cover traffic* (falešné zprávy) — to
  není implementováno.
- **Globální pasivní odposlech**, který vidí síť obou stran zároveň, může zkoušet
  časovou korelaci. Tor to ztěžuje, ne však dokonale.
- **Kompromitovaný telefon** má klíče v sobě; to brání zámek aplikace a Android
  Keystore, ne relay.
- Když je server zabaven za běhu, drží nanejvýš pár prchavých, end-to-end
  šifrovaných blobků (žádné logy, žádná historie, žádná metadata o tom, komu
  patří), které do TTL expirují.
