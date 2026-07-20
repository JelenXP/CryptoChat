#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CryptoChat Relay - "slepa schranka" (dead-drop) pro zero-knowledge dorucovani.

Server je zamerne HLOUPY. Umi jen dve veci:
  - PUT /m/<mailbox_id>  ulozi zasifrovany blob do schranky
  - GET /m/<mailbox_id>  vrati vsechny cekajici bloby a schranku vyprazdni

Co server NIKDY nevi (a ani vedet nemuze):
  - obsah zprav        -> jsou E2E zasifrovane uz v telefonu (AES-256-GCM)
  - kdo komu pise       -> zadne ucty, zadna jmena. Jen nahodne vypadajici ID
                           schranky, ktere si obe strany spocitaji z klice
                           (HKDF) - server ho nikdy nevidel.
  - delku zpravy        -> klient bloby paddinguje na fixni velikosti.

Jedina vyjimka je POST /report: dobrovolne hlaseni chyby, ktere uzivatel posle
z appky (a predem vidi, co presne odesila). Uklada se do CC_REPORTS_DIR a je uz
od klienta anonymni - zadne zpravy, klice, jmena kontaktu ani ID schranek.

Jinak server drzi vse jen v PAMETI, nezaznamenava zadne logy pristupu
a schranky maji kratkou zivotnost (TTL) + mazou se po prvnim vyzvednuti. I kdyby
nekdo server zabavil, najde jen par sifrovanych blobku, ktere za chvili mizi.

Zadne zavislosti - jen standardni knihovna Pythonu 3. Spusteni: `python3 server.py`.
Konfigurace pres promenne prostredi (viz nize), vse ma rozumny vychozi stav.
"""

import os
import re
import secrets
import struct
import threading
import tempfile
import time
import urllib.parse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


# --- Konfigurace (prepsatelna promennymi prostredi) --------------------------

# Naslouchaci adresa. Vychozi 127.0.0.1 = jen mistni pripojeni, protoze relay
# ma bezet ZA Tor onion service (Tor se pripojuje lokalne). Pokud chces server
# vystavit primo do site (nedoporuceno kvuli soukromi), nastav CC_HOST=0.0.0.0.
HOST = os.environ.get("CC_HOST", "127.0.0.1")
PORT = int(os.environ.get("CC_PORT", "8787"))

# Maximalni velikost jednoho blobu (bajty). Textove zpravy jsou male, ale fotky a
# kousky (chunky) vetsich souboru potrebuji vic - proto 2 MB.
MAX_BLOB_SIZE = int(os.environ.get("CC_MAX_BLOB_SIZE", str(2 * 1024 * 1024)))  # 2 MB

# Kdyz prijde prilis velke telo, chceme klientovi poslat cistou odpoved 413.
# Aby ji stihl precist (a nedostal reset spojeni), musime zbytek tela "vypit".
# Vypijeme ale jen do tohoto stropu - vetsi zneuzivajici uploady rovnou odpojime.
DRAIN_CAP = int(os.environ.get("CC_DRAIN_CAP", str(8 * 1024 * 1024)))          # 8 MB

# Kolik blobku smi cekat v jedne schrance (napr. kdyz odesilatel posle vic zprav,
# nez si prijemce stihne vyzvednout).
MAX_MAILBOX_BLOBS = int(os.environ.get("CC_MAX_MAILBOX_BLOBS", "200"))

# Za jak dlouho nevyzvednuta schranka expiruje (sekundy).
TTL_SECONDS = int(os.environ.get("CC_TTL_SECONDS", str(24 * 3600)))            # 24 h

# Globalni strop pameti (bajty) - pojistka proti zaplneni RAM serveru. Pri prekroceni
# server odmita nove PUT, dokud se neco nevyzvedne / neexpiruje.
MAX_TOTAL_BYTES = int(os.environ.get("CC_MAX_TOTAL_BYTES", str(512 * 1024 * 1024)))  # 512 MB

# Strop soubeznych spojeni. Kazde spojeni = jedno vlakno; pri dlouhem long-pollu
# jich zije hodne najednou, ale musi to mit konec. Pocitej ~2 spojeni na aktivni
# kontakt a rezervu: 512 vlaken notebook v pohode unese.
MAX_CONNECTIONS = int(os.environ.get("CC_MAX_CONNECTIONS", "512"))

# Jednoduchy rate limit na klienta (pocet pozadavku za okno). Za Torem prichazi
# vsichni z 127.0.0.1, takze je to ve skutecnosti JEDEN SPOLECNY kbelik pro
# vsechny uzivatele - proti utocnikovi nechrani (ten ho jen vycerpa) a pri nizke
# hodnote by naopak odstrihl legitimni provoz. Skutecnou obranou je proto
# MAX_CONNECTIONS vyse; tenhle limit je uz jen pojistka pri primem vystaveni do
# site, a proto je nastaveny volne.
RATE_LIMIT_REQUESTS = int(os.environ.get("CC_RATE_LIMIT_REQUESTS", "3000"))
RATE_LIMIT_WINDOW = int(os.environ.get("CC_RATE_LIMIT_WINDOW", "60"))          # sekundy

# Long-polling: kdyz klient posle GET s ?wait=<s>, drzime spojeni otevrene az
# tolik sekund a odpovime hned, jak dorazi zprava (min round-tripu pres Tor,
# skoro okamzite doruceni). Strop drzime pod ctecim timeoutem klienta.
#
# Cim delsi cekani, tim min probuzeni klienta = min vybite baterie: 90 s misto
# 25 s snizi pocet round-tripu na ctvrtinu, aniz by se zdrzelo doruceni (PUT
# cekajici GET probudi okamzite). Vlakno navic nic nestoji - jen ceka na Condition.
LONGPOLL_MAX = int(os.environ.get("CC_LONGPOLL_MAX", "90"))                     # sekundy

# --- Hlaseni chyb (POST /report) ---------------------------------------------
#
# Jedina vec, kterou server uklada na DISK. Hlaseni posila uzivatel dobrovolne
# z obrazovky "Nahlasit chybu" a vidi predem, co presne odesle. Obsah je uz od
# klienta anonymni (zadne zpravy, klice, jmena kontaktu ani ID schranek) a chodi
# pres Tor, takze o odesilateli nic nevime - a nic o nem ani neukladame.
def _pick_reports_dir():
    """Vybere prvni ZAPISOVATELNY adresar pro hlaseni chyb.

    Sluzba bezi pod vlastnim uzivatelem a s WorkingDirectory v /opt, ktere patri
    rootovi - relativni "./reports" tam nejde vytvorit a kazde hlaseni koncilo
    chybou 500. Zkousime proto poradi kandidatu a prvni funkcni si necháme.
    Explicitni CC_REPORTS_DIR ma vzdy prednost.
    """
    candidates = []
    explicit = os.environ.get("CC_REPORTS_DIR")
    if explicit:
        candidates.append(explicit)
    candidates.append("/var/lib/cryptochat-relay/reports")
    home = os.path.expanduser("~")
    if home and home != "~":
        candidates.append(os.path.join(home, "cryptochat-reports"))
    candidates.append(os.path.join(tempfile.gettempdir(), "cryptochat-reports"))
    for path in candidates:
        try:
            os.makedirs(path, exist_ok=True)
            probe = os.path.join(path, ".write-test")
            with open(probe, "wb") as handle:
                handle.write(b"x")
            os.remove(probe)
            return path
        except Exception:
            continue
    return None


REPORTS_DIR = _pick_reports_dir()

# Strop velikosti jednoho hlaseni. Klient si telo sam zkracuje pod tuto mez.
MAX_REPORT_SIZE = int(os.environ.get("CC_MAX_REPORT_SIZE", str(256 * 1024)))   # 256 KB

# Kolik bajtu nejvys vratit v JEDNE odpovedi na peek. Klient ma vlastni strop na
# velikost odpovedi; kdyby ho schranka prekrocila, nesla by uz nikdy precist.
MAX_PEEK_BYTES = int(os.environ.get("CC_MAX_PEEK_BYTES", str(8 * 1024 * 1024)))  # 8 MB

# Strop pro hlaseni chyb na disku: pocet slozek a celkova velikost. Bez nej by
# kdokoli se znalosti onion adresy zaplnil disk serveru.
MAX_REPORTS = int(os.environ.get("CC_MAX_REPORTS", "500"))
MAX_REPORTS_BYTES = int(os.environ.get("CC_MAX_REPORTS_BYTES", str(64 * 1024 * 1024)))

# Povoleny tvar mailbox ID: URL-safe base64 / hex, rozumna delka. Server ho bere
# jako neprusvitny retezec - nezajima ho, co znamena.
MAILBOX_ID_RE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")


# --- Uloziste schranek (jen v pameti) ----------------------------------------

class MailboxStore:
    """Vlakno-bezpecne uloziste: mailbox_id -> fronta blobku + cas expirace.

    Vse zije jen v RAM. Restart serveru = vse zmizi (u relaye je to v poradku,
    zpravy jsou pomijive a odesilatel je muze poslat znovu na dalsi schranku).
    """

    def __init__(self):
        # Condition (misto prosteho Locku) umoznuje long-polling: GET, ktery najde
        # prazdnou schranku, muze pockat, nez ho PUT probudi (notify_all).
        self._cond = threading.Condition()
        # Poradove cislo je GLOBALNI a nikdy se neresetuje.
        #
        # Puvodne bylo per-schranka a zacinalo od 1. Jenze schranka po vyzvednuti
        # (ack/drain/TTL) zanikne a dalsi PUT ji zalozi znovu - takze cislovani
        # zacalo od zacatku. Opozdene potvrzeni "upto=5" (pres Tor se DELETE
        # opakuje i desitky sekund) pak mohlo smazat uplne nove zpravy, ktere
        # klient nikdy nevidel. Seed z casu navic zajisti, ze ani potvrzeni
        # z doby pred restartem serveru nesmaze nic noveho.
        self._next_seq = time.time_ns() // 1000
        # mailbox_id -> {"blobs": deque[(seq, bytes)], "expires": float}
        #
        # Kazdy blob ma poradove cislo v ramci schranky. Diky nemu muze klient
        # rict "zpracoval jsem vse do N" a teprve tehdy se bloby smazou - viz
        # peek/ack nize.
        self._boxes = {}
        self._total_bytes = 0

    def put(self, mailbox_id: str, blob: bytes) -> str:
        """Prida blob do schranky. Vraci 'ok' | 'full' | 'box_full'."""
        now = time.monotonic()
        with self._cond:
            if self._total_bytes + len(blob) > MAX_TOTAL_BYTES:
                # Drive se tady jen vratilo 507 a nic se nemazalo - utocnik tak
                # par sty bloby zablokoval zapis VSEM na celou dobu TTL (24 h).
                # Ted radeji obetujeme nejstarsi schranky (ty stejne expiruji
                # nejdriv) a provoz jede dal.
                self._evict_locked(len(blob))
            if self._total_bytes + len(blob) > MAX_TOTAL_BYTES:
                return "full"
            box = self._boxes.get(mailbox_id)
            if box is None:
                box = {"blobs": deque(), "expires": now + TTL_SECONDS}
                self._boxes[mailbox_id] = box
            if len(box["blobs"]) >= MAX_MAILBOX_BLOBS:
                return "box_full"
            self._next_seq += 1
            box["blobs"].append((self._next_seq, blob))
            box["expires"] = now + TTL_SECONDS  # kazdy zapis prodlouzi zivotnost
            self._total_bytes += len(blob)
            # Probud pripadne long-poll cekatele (vsichni si znovu overi svou schranku).
            self._cond.notify_all()
            return "ok"

    def drain(self, mailbox_id: str):
        """Vrati a SMAZE vsechny cekajici bloby ze schranky (list[bytes])."""
        with self._cond:
            return [b for _seq, b in self._drain_locked(mailbox_id)]

    def drain_blocking(self, mailbox_id: str, timeout: float):
        """Jako [drain], ale kdyz je schranka prazdna, pocka az [timeout] sekund,
        nez ji nejaky PUT naplni. Vraci list[bytes] (prazdny = timeout bez zpravy).
        Drzi jedno serverove vlakno po dobu cekani - klient posila jen kdyz otevre
        chat, takze cekatelu je malo."""
        return [b for _seq, b in self._peek_wait(mailbox_id, timeout, drain=True)]

    def peek_blocking(self, mailbox_id: str, timeout: float):
        """Jako [drain_blocking], ale bloby NEMAZE - vrati (seq, blob).

        Proc: kdyz spojeni spadne uprostred odpovedi (rozpadly Tor okruh, prepnuta
        wifi), klient data nedostane - a pri mazani pri cteni uz by na serveru
        nebyla, tedy nenavratne ztracena zprava. Takhle zustanou lezet a smazou se
        az potvrzenim [ack]. Opakovane doruceni si klient odfiltruje sam (zna
        otisky uz zpracovanych blobu).
        """
        return self._peek_wait(mailbox_id, timeout, drain=False)

    def _peek_wait(self, mailbox_id: str, timeout: float, drain: bool):
        deadline = time.monotonic() + timeout
        with self._cond:
            while True:
                box = self._boxes.get(mailbox_id)
                if box is not None and box["blobs"]:
                    if drain:
                        return self._drain_locked(mailbox_id)
                    # Vratime jen tolik, kolik se vejde do rozumne odpovedi.
                    # Schranka smi drzet az MAX_MAILBOX_BLOBS * MAX_BLOB_SIZE
                    # (stovky MB) - poslat to najednou by klient nemel kam ulozit
                    # a schranka by byla TRVALE necitelna. Diky poradovym cislum
                    # staci vratit prefix; klient ho potvrdi a zbytek dostane
                    # v dalsim kole.
                    out = []
                    total = 0
                    for seq, blob in box["blobs"]:
                        if out and total + len(blob) > MAX_PEEK_BYTES:
                            break
                        out.append((seq, blob))
                        total += len(blob)
                    return out
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return []
                self._cond.wait(remaining)

    def ack(self, mailbox_id: str, upto_seq: int):
        """Smaze bloby se seq <= upto_seq (klient je ma bezpecne ulozene)."""
        with self._cond:
            box = self._boxes.get(mailbox_id)
            if box is None:
                return
            blobs = box["blobs"]
            while blobs and blobs[0][0] <= upto_seq:
                _seq, blob = blobs.popleft()
                self._total_bytes -= len(blob)
            if not blobs:
                self._boxes.pop(mailbox_id, None)

    def _evict_locked(self, needed: int):
        """Uvolni misto zahozenim nejstarsich schranek. Vola se uz drzici self._cond.

        Maze se v poradi podle expirace (nejdriv ty, ktere by stejne brzy zmizely),
        dokud neni misto pro [needed] bajtu a jeste rezerva, aby se neuklizelo
        pri kazdem dalsim zapisu.
        """
        target = MAX_TOTAL_BYTES - needed - (MAX_TOTAL_BYTES // 10)
        if self._total_bytes <= target:
            return
        for mid, _box in sorted(self._boxes.items(), key=lambda kv: kv[1]["expires"]):
            if self._total_bytes <= target:
                break
            self._drain_locked(mid)

    def _drain_locked(self, mailbox_id: str):
        """Vyzvedne a smaze schranku. Vraci [(seq, blob)]. Vola se uz drzici self._cond."""
        box = self._boxes.pop(mailbox_id, None)
        if box is None:
            return []
        items = list(box["blobs"])
        self._total_bytes -= sum(len(b) for _seq, b in items)
        return items

    def purge_expired(self):
        """Odstrani expirovane schranky. Vola se periodicky z uklidoveho vlakna."""
        now = time.monotonic()
        with self._cond:
            dead = [mid for mid, box in self._boxes.items() if box["expires"] <= now]
            for mid in dead:
                box = self._boxes.pop(mid)
                self._total_bytes -= sum(len(b) for _seq, b in box["blobs"])


STORE = MailboxStore()


# --- Rate limiter -------------------------------------------------------------

class RateLimiter:
    """Klouzave okno na klienta (klic = IP). Za Torem je klic vzdy 127.0.0.1."""

    def __init__(self, limit: int, window: int):
        self._limit = limit
        self._window = window
        self._lock = threading.Lock()
        self._hits = {}  # klic -> deque[timestamp]

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            dq = self._hits.get(key)
            if dq is None:
                dq = deque()
                self._hits[key] = dq
            while dq and dq[0] <= now - self._window:
                dq.popleft()
            if len(dq) >= self._limit:
                return False
            dq.append(now)
            return True


LIMITER = RateLimiter(RATE_LIMIT_REQUESTS, RATE_LIMIT_WINDOW)


# --- Ukladani hlaseni chyb ----------------------------------------------------

def _enforce_reports_quota(incoming: int):
    """Udrzi slozku hlaseni pod stropem: maze nejstarsi, dokud se nove nevejde.

    Bez toho by kdokoli se znalosti onion adresy zaplnil disk serveru (hlaseni
    smi mit 256 KB a rate limit je za Torem spolecny pro vsechny).
    """
    try:
        entries = []
        total = 0
        for name in os.listdir(REPORTS_DIR):
            path = os.path.join(REPORTS_DIR, name)
            if not os.path.isdir(path):
                continue
            size = 0
            for root, _dirs, files in os.walk(path):
                for f in files:
                    try:
                        size += os.path.getsize(os.path.join(root, f))
                    except OSError:
                        pass
            entries.append((name, path, size))
            total += size
        entries.sort(key=lambda e: e[0])          # nazev zacina timestampem
        count = len(entries)
        for _name, path, size in entries:
            if count + 1 <= MAX_REPORTS and total + incoming <= MAX_REPORTS_BYTES:
                break
            try:
                for root, dirs, files in os.walk(path, topdown=False):
                    for f in files:
                        os.remove(os.path.join(root, f))
                    for d in dirs:
                        os.rmdir(os.path.join(root, d))
                os.rmdir(path)
                count -= 1
                total -= size
            except OSError:
                break
    except Exception:
        # Uklid nesmi shodit prijem hlaseni.
        pass


def store_report(body: bytes) -> bool:
    """Ulozi hlaseni do vlastni slozky reports/<timestamp>-<suffix>/report.json.

    Telo se uklada PRESNE tak, jak prislo - server ho nijak neparsuje ani
    nedoplnuje. Zejmena se NEUKLADA nic o odesilateli (za Torem stejne nic neni:
    vsechna spojeni chodi z 127.0.0.1).

    Nahodny suffix ma dva duvody: kolize dvou hlaseni ve stejne sekunde a to,
    aby se z nazvu slozek nedal odhadnout pocet ani poradi hlaseni.

    Vraci True pri uspechu. Chybu zapisu NIKDY nepusti ven - hlaseni chyby nesmi
    shodit relay, ktery jinak dorucuje zpravy.
    """
    if REPORTS_DIR is None:
        return False
    try:
        _enforce_reports_quota(len(body))
        stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
        folder = os.path.join(REPORTS_DIR, stamp + "-" + secrets.token_hex(4))
        os.makedirs(folder)
        with open(os.path.join(folder, "report.json"), "wb") as handle:
            handle.write(body)
        return True
    except Exception:
        return False


# --- HTTP handler -------------------------------------------------------------

class RelayHandler(BaseHTTPRequestHandler):
    # Protokol nechame HTTP/1.1 (keep-alive), rychlejsi pri pollingu.
    protocol_version = "HTTP/1.1"

    # Timeout na necinne spojeni. BEZ NEJ je socket bez timeoutu a vlakno visi
    # navzdy v readline() - utocnik otevre par tisic spojeni, nic neposle a
    # server dojdou vlakna (slowloris). Musi byt delsi nez nejdelsi long-poll,
    # jinak by se rusila legitimni cekani.
    timeout = LONGPOLL_MAX + 60

    # Zadne logy pristupu - soukromi. Prepisujeme na no-op.
    def log_message(self, *args, **kwargs):  # noqa: D401
        pass

    # Skryjeme konkretni verzi serveru (min informaci utocnikovi).
    def version_string(self):
        return "CryptoChatRelay"

    # --- pomocnici ---

    def _client_key(self) -> str:
        return self.client_address[0] if self.client_address else "unknown"

    def _send(self, code: int, body: bytes = b"", content_type: str = "application/octet-stream",
              close: bool = False, extra_headers: dict = None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        # Zadne cache, zadne stopy.
        self.send_header("Cache-Control", "no-store")
        if close:
            # Ukoncime spojeni po teto odpovedi (napr. kdyz nedopijeme telo).
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _drain_body(self, length: int) -> bool:
        """Vypije (zahodi) telo pozadavku. Vraci True, pokud se povedlo cele
        precist (spojeni se da drzet dal), jinak False (radsi zavrit)."""
        if length > DRAIN_CAP:
            return False
        remaining = length
        while remaining > 0:
            chunk = self.rfile.read(min(65536, remaining))
            if not chunk:
                return False
            remaining -= len(chunk)
        return True

    def _mailbox_id_from_path(self):
        # Ocekavame /m/<id>. Query string ignorujeme.
        path = self.path.split("?", 1)[0]
        if not path.startswith("/m/"):
            return None
        mid = path[len("/m/"):]
        if not MAILBOX_ID_RE.match(mid):
            return None
        return mid

    def _content_length(self):
        """Vrati Content-Length jako int, nebo None kdyz chybi/je nevalidni."""
        length_hdr = self.headers.get("Content-Length")
        if length_hdr is None:
            return None
        try:
            return int(length_hdr)
        except ValueError:
            return None

    def _longpoll_seconds(self) -> int:
        """Precte ?wait=<s> z URL a orizne na [0, LONGPOLL_MAX]. 0 = bez cekani."""
        split = self.path.split("?", 1)
        if len(split) < 2:
            return 0
        raw = urllib.parse.parse_qs(split[1]).get("wait", ["0"])[0]
        try:
            return max(0, min(int(raw), LONGPOLL_MAX))
        except ValueError:
            return 0

    # --- routy ---

    def do_GET(self):
        if not LIMITER.allow(self._client_key()):
            self._send(429, b"rate limited\n", "text/plain")
            return

        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(200, b"ok\n", "text/plain")
            return

        mid = self._mailbox_id_from_path()
        if mid is None:
            self._send(404)
            return

        # Long-poll: s ?wait=<s> pockame, nez dorazi zprava (jinak hned vyzvedneme).
        wait_s = self._longpoll_seconds()

        # Rezim potvrzovaneho cteni (?ack=1): bloby se NEMAZOU, jen se vrati i s
        # poradovym cislem. Klient je smaze az potvrzenim (DELETE ...?upto=N),
        # tedy AZ kdyz je ma bezpecne ulozene. Bez toho by kazde spojeni prerusene
        # uprostred odpovedi znamenalo nenavratne ztracenou zpravu.
        # Bez parametru zustava puvodni (destruktivni) chovani kvuli starsim klientum.
        if self._wants_ack():
            items = (STORE.peek_blocking(mid, wait_s) if wait_s > 0
                     else STORE.peek_blocking(mid, 0))
            if not items:
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            out = bytearray()
            for _seq, b in items:
                out += struct.pack(">I", len(b))
                out += b
            self._send(200, bytes(out), extra_headers={"X-CC-Seq": str(items[-1][0])})
            return

        blobs = STORE.drain_blocking(mid, wait_s) if wait_s > 0 else STORE.drain(mid)
        # Strop i tady: schranka smi mit stovky MB a slozit je do jedne odpovedi
        # by znamenalo nekolikanasobnou spicku v pameti serveru.
        if blobs:
            capped = []
            total = 0
            for b in blobs:
                if capped and total + len(b) > MAX_PEEK_BYTES:
                    break
                capped.append(b)
                total += len(b)
            blobs = capped
        if not blobs:
            # Prazdna schranka - 204 No Content (kratke, bez tela).
            self.send_response(204)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            return

        # Vice blobku posleme delkove ramovane: pro kazdy [4B BE delka][data].
        # Klient cte 4 bajty delky, pak tolik bajtu, dokud stream neskonci -
        # stejny styl ramovani, jaky uz appka pouziva u sifrovani souboru.
        out = bytearray()
        for b in blobs:
            out += struct.pack(">I", len(b))
            out += b
        self._send(200, bytes(out))

    def do_DELETE(self):
        """Potvrzeni prijmu: smaze bloby se seq <= upto (viz do_GET, rezim ack)."""
        if not LIMITER.allow(self._client_key()):
            self._send(429, b"rate limited\n", "text/plain")
            return
        mid = self._mailbox_id_from_path()
        if mid is None:
            self._send(404)
            return
        upto = self._query_int("upto", -1)
        if upto < 0:
            self._send(400)
            return
        STORE.ack(mid, upto)
        self.send_response(204)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _wants_ack(self) -> bool:
        return self._query_int("ack", 0) == 1

    def _query_int(self, name: str, default: int) -> int:
        split = self.path.split("?", 1)
        if len(split) != 2:
            return default
        raw = urllib.parse.parse_qs(split[1]).get(name, [None])[0]
        if raw is None:
            return default
        try:
            return int(raw)
        except ValueError:
            return default

    def do_PUT(self):
        if not LIMITER.allow(self._client_key()):
            self._send(429, b"rate limited\n", "text/plain")
            return

        mid = self._mailbox_id_from_path()
        if mid is None:
            self._send(404)
            return

        length = self._content_length()
        if length is None or length <= 0:
            self._send(411, b"length required\n", "text/plain", close=True)
            return
        if length > MAX_BLOB_SIZE:
            # Telo je moc velke - odmitneme, ale zbytek "vypijeme", aby klient
            # stihl precist 413. Prilis velke (nad DRAIN_CAP) rovnou odpojime.
            drained = self._drain_body(length)
            self._send(413, b"blob too large\n", "text/plain", close=not drained)
            return

        blob = self.rfile.read(length)
        result = STORE.put(mid, blob)
        if result == "ok":
            self._send(204)
        elif result == "box_full":
            self._send(409, b"mailbox full\n", "text/plain")
        else:  # "full"
            self._send(507, b"server storage full\n", "text/plain")

    def do_POST(self):
        # /report je jediny POST s vlastni obsluhou; zbytek zustava aliasem
        # k PUT (nekteri klienti posilaji radeji POST).
        if self.path.split("?", 1)[0] == "/report":
            self._handle_report()
            return
        self.do_PUT()

    def _handle_report(self):
        """Prijme dobrovolne hlaseni chyby a ulozi ho na disk (viz store_report)."""
        if not LIMITER.allow(self._client_key()):
            self._send(429, b"rate limited\n", "text/plain")
            return

        length = self._content_length()
        if length is None or length <= 0:
            self._send(411, b"length required\n", "text/plain", close=True)
            return
        if length > MAX_REPORT_SIZE:
            # Stejne jako u prilis velkeho blobu: zbytek "vypijeme", aby klient
            # stihl precist 413, a pri extremni velikosti rovnou odpojime.
            drained = self._drain_body(length)
            self._send(413, b"report too large\n", "text/plain", close=not drained)
            return

        body = self.rfile.read(length)
        if len(body) != length:
            self._send(400, b"short body\n", "text/plain", close=True)
            return

        if store_report(body):
            self._send(204)
        else:
            self._send(500, b"cannot store report\n", "text/plain")


# --- Uklidove vlakno + spusteni ----------------------------------------------

def _purge_loop(stop_event: threading.Event):
    """Kazdych ~30 s smaze expirovane schranky.

    Vyjimku uvnitr NESMIME nechat probublat: vlakno by tise umrelo (logy jsou
    kvuli soukromi vypnute), schranky by prestaly expirovat a pamet by rostla
    az na strop - tedy trvaly vypadek, ktery by nikdo nezaznamenal.
    """
    while not stop_event.wait(30):
        try:
            STORE.purge_expired()
        except Exception:
            # Zamlcet a zkusit za 30 s znovu - dulezite je, ze vlakno zije dal.
            pass


class RelayServer(ThreadingHTTPServer):
    """ThreadingHTTPServer se STROPEM soubeznych spojeni.

    Vychozi ThreadingHTTPServer vytvori vlakno na kazde spojeni bez omezeni. Za
    Torem nejde omezovat podle IP (vsichni prichazi z 127.0.0.1), takze jedina
    obrana proti vycerpani vlaken je tvrdy strop: nad nej se spojeni rovnou
    zavira, misto aby server upadl do swapu / spadl na 'can't start new thread'.
    """

    daemon_threads = True

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._slots = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            try:
                request.close()
            except OSError:
                pass
            return
        super().process_request(request, client_address)

    def shutdown_request(self, request):
        try:
            super().shutdown_request(request)
        finally:
            self._slots.release()


def main():
    server = RelayServer((HOST, PORT), RelayHandler)

    stop_event = threading.Event()
    purger = threading.Thread(target=_purge_loop, args=(stop_event,), daemon=True)
    purger.start()

    print(f"CryptoChat relay bezi na http://{HOST}:{PORT}")
    print(f"  max blob: {MAX_BLOB_SIZE} B | TTL: {TTL_SECONDS} s | "
          f"max pamet: {MAX_TOTAL_BYTES} B")
    print("  hlaseni chyb (POST /report) -> " + (os.path.abspath(REPORTS_DIR) if REPORTS_DIR else "VYPNUTO (zadny zapisovatelny adresar)"))
    print("  (Ctrl+C pro ukonceni)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        server.shutdown()
        print("\nRelay ukoncen.")


if __name__ == "__main__":
    main()
