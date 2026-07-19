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

Server drzi vse jen v PAMETI (nic na disk), nezaznamenava zadne logy pristupu
a schranky maji kratkou zivotnost (TTL) + mazou se po prvnim vyzvednuti. I kdyby
nekdo server zabavil, najde jen par sifrovanych blobku, ktere za chvili mizi.

Zadne zavislosti - jen standardni knihovna Pythonu 3. Spusteni: `python3 server.py`.
Konfigurace pres promenne prostredi (viz nize), vse ma rozumny vychozi stav.
"""

import os
import re
import struct
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


# --- Konfigurace (prepsatelna promennymi prostredi) --------------------------

# Naslouchaci adresa. Vychozi 127.0.0.1 = jen mistni pripojeni, protoze relay
# ma bezet ZA Tor onion service (Tor se pripojuje lokalne). Pokud chces server
# vystavit primo do site (nedoporuceno kvuli soukromi), nastav CC_HOST=0.0.0.0.
HOST = os.environ.get("CC_HOST", "127.0.0.1")
PORT = int(os.environ.get("CC_PORT", "8787"))

# Maximalni velikost jednoho blobu (bajty). Zpravy jsou male; strop je pojistka.
MAX_BLOB_SIZE = int(os.environ.get("CC_MAX_BLOB_SIZE", str(512 * 1024)))       # 512 KB

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
MAX_TOTAL_BYTES = int(os.environ.get("CC_MAX_TOTAL_BYTES", str(128 * 1024 * 1024)))  # 128 MB

# Jednoduchy rate limit na klienta (pocet pozadavku za okno). Za Torem prichazi
# vsichni z 127.0.0.1, takze tohle chrani hlavne pri primem vystaveni do site.
RATE_LIMIT_REQUESTS = int(os.environ.get("CC_RATE_LIMIT_REQUESTS", "240"))
RATE_LIMIT_WINDOW = int(os.environ.get("CC_RATE_LIMIT_WINDOW", "60"))          # sekundy

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
        self._lock = threading.Lock()
        # mailbox_id -> {"blobs": deque[bytes], "expires": float(monotonic)}
        self._boxes = {}
        self._total_bytes = 0

    def put(self, mailbox_id: str, blob: bytes) -> str:
        """Prida blob do schranky. Vraci 'ok' | 'full' | 'box_full'."""
        now = time.monotonic()
        with self._lock:
            if self._total_bytes + len(blob) > MAX_TOTAL_BYTES:
                return "full"
            box = self._boxes.get(mailbox_id)
            if box is None:
                box = {"blobs": deque(), "expires": now + TTL_SECONDS}
                self._boxes[mailbox_id] = box
            if len(box["blobs"]) >= MAX_MAILBOX_BLOBS:
                return "box_full"
            box["blobs"].append(blob)
            box["expires"] = now + TTL_SECONDS  # kazdy zapis prodlouzi zivotnost
            self._total_bytes += len(blob)
            return "ok"

    def drain(self, mailbox_id: str):
        """Vrati a SMAZE vsechny cekajici bloby ze schranky (list[bytes])."""
        with self._lock:
            box = self._boxes.pop(mailbox_id, None)
            if box is None:
                return []
            blobs = list(box["blobs"])
            self._total_bytes -= sum(len(b) for b in blobs)
            return blobs

    def purge_expired(self):
        """Odstrani expirovane schranky. Vola se periodicky z uklidoveho vlakna."""
        now = time.monotonic()
        with self._lock:
            dead = [mid for mid, box in self._boxes.items() if box["expires"] <= now]
            for mid in dead:
                box = self._boxes.pop(mid)
                self._total_bytes -= sum(len(b) for b in box["blobs"])


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


# --- HTTP handler -------------------------------------------------------------

class RelayHandler(BaseHTTPRequestHandler):
    # Protokol nechame HTTP/1.1 (keep-alive), rychlejsi pri pollingu.
    protocol_version = "HTTP/1.1"

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
              close: bool = False):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
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

        blobs = STORE.drain(mid)
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

    # Nekteri klienti (OkHttp) posilaji radeji POST - povolime jako alias k PUT.
    def do_POST(self):
        self.do_PUT()


# --- Uklidove vlakno + spusteni ----------------------------------------------

def _purge_loop(stop_event: threading.Event):
    """Kazdych ~30 s smaze expirovane schranky."""
    while not stop_event.wait(30):
        STORE.purge_expired()


def main():
    server = ThreadingHTTPServer((HOST, PORT), RelayHandler)
    server.daemon_threads = True

    stop_event = threading.Event()
    purger = threading.Thread(target=_purge_loop, args=(stop_event,), daemon=True)
    purger.start()

    print(f"CryptoChat relay bezi na http://{HOST}:{PORT}")
    print(f"  max blob: {MAX_BLOB_SIZE} B | TTL: {TTL_SECONDS} s | "
          f"max pamet: {MAX_TOTAL_BYTES} B")
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
