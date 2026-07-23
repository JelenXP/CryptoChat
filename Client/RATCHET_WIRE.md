# Drátový kontrakt: Double Ratchet (rotace klíčů)

Tento dokument popisuje drátový kontrakt in-band rotace klíčů (Double Ratchet,
R2 + PCS). **Autoritou je KÓD** (`chat/ChatEnvelope.kt`, `chat/RelayCrypto.kt`,
`chat/DoubleRatchet.kt`, `chat/RelaySync.kt`) a zamražené golden vzorky
(`ChatEnvelopeRatchetGoldenTest`); tenhle text je jejich průvodce. Změna kontraktu
= zamražený golden vzorek navíc (nikdy neupravovat stávající).

> **POZOR — návrh vs. realita (nález v2.0-34 / A-N2).** Fáze-2 části (symetrický
> ratchet, adresování, epochy) SEDÍ s implementací. **Fáze-4 KEM krok se ale při
> implementaci posunul** a části níže popisují PŮVODNÍ návrh, ne odeslané bajty:
> - **KEM materiál NENÍ v otevřené hlavičce.** Re-key jede jako **in-band řídicí
>   zprávy** (OFFER/ACCEPT/CONFIRM, `kind` uvnitř šifry — viz `ChatEnvelope`
>   `buildRekey*` a `RelaySync.handleRekey`), ne jako `pub`/`ct` oddíl v hlavičce.
>   `htype` je proto přesná hodnota `0x00`/`0x02` (= přítomna generace), **ne
>   bitpole** „bit0 = KEM oddíl".
> - **Kořenový KDF label** je `CryptoChat/ratchet/rekey-root/v1|gen=<g>`
>   (`DoubleRatchet.REKEY_ROOT`), ne `.../kem-root/v1`.
> - **KEM krok NEposouvá epochu** (`applyRekey` se `sendEpoch`/`msgsSinceEpoch`
>   nedotýká); epochu žene JEN čítač `K` (`RATCHET_EPOCH_MSGS`).
>
> Fáze-4 sekce ponechány jako historický návrh, aby se golden vzorky a čísla
> generací daly dohledat; při jakékoli změně re-key se řiď KÓDEM, ne jimi.

Souvisí s pravidly ve `WireCompat` (major migrace, capability) a s datovým
modelem `RatchetState` (Fáze 1). Legacy formát (major 3) se NEMĚNÍ.

Souvisí s pravidly ve `WireCompat` (major migrace, capability) a s datovým
modelem `RatchetState` (Fáze 1). Legacy formát (major 3) se NEMĚNÍ.

---

## 0. Konstanty

| konstanta | hodnota | význam |
|---|---|---|
| `WIRE_MAJOR_RATCHET` | **4** | otevřený major bajt ratchet blobu |
| `RATCHET_EPOCH_MSGS` (K) | **32** | posuň epochu po každých K odeslaných zprávách (Návrh 2) |
| `RATCHET_LOOKAHEAD` (W) | **8** | kolik kandidátních schránek dopředu příjemce pollne |
| `RATCHET_SKIP_MAX` | **1000** | strop přeskočených klíčů / max. přetočení řetězu; nad → karanténa |
| `EPOCH_BYTES` / `MSGNO_BYTES` | 4 / 4 | u32 BE |

Všechny KDF = **HKDF-SHA256** (RFC 5869), stejná implementace jako
`RelayCrypto.hkdf` (salt = 32 nul, doménová separace přes `info` label).
Doménové labely jsou **odlišné od legacy** (`INFO_MAILBOX` apod.), takže se
ratchet klíče ani schránky nikdy nepotkají se starými.

---

## 1. Koexistence s legacy (major 3) a migrace

Čtení přijímá **množinu majorů**: `{3 = legacy statický klíč, 4 = ratchet}`.
Odeslání je **vyjednané per kontakt**, výchozí je legacy (3).

- `WireCompat.MAX_READABLE_MAJOR` = **4** (umíme přečíst 4). `WIRE_MAJOR`
  (výchozí odeslání) zůstává **3**.
- Každá zpráva už dnes inzeruje `TYPE_MAX_MAJOR = MAX_READABLE_MAJOR` v traileru
  → protějšek se autentizovaně dozví, že umíme major 4.
- **Gate přepnutí na ratchet:** posílej major 4 kontaktu, teprve když
  `WireCompat.peerCanReadMajor(ctx, id, 4) == true` (protějšek inzeroval
  `maxMajor ≥ 4`) **a** lokální `RatchetState` je bootstrapnutý. Do té doby legacy.
- **DUAL režim (překryv):** jakmile je zaznamenáno `peer.maxMajor ≥ 4`, začni
  pollovat i ratchet schránky (vedle legacy denních). Odesílej pořád legacy,
  dokud neplatí gate výše; po přepnutí poslouchej legacy ještě grace period, ať
  doběhnou zprávy v letu.
- **Bootstrap bez handshake zprávy:** obě strany deterministicky odvodí `RK_0`
  z `M` (viz §4). První major-4 zpráva je sebe-popisná (nese epochu i pořadí),
  příjemce ji přečte z ratchet schránky přímo.

`RatchetState.Mode`: `LEGACY` → `DUAL` (peer.maxMajor≥4, posloucháme obojí,
posíláme legacy) → `RATCHET` (gate splněn, posíláme major 4).

Legacy verze (umí jen major 3), která by ratchet blob přece jen dostala (nemá
nastat — gate), ho podle `readMajor != 3` odloží do karantény (30 dní, neškodné).

---

## 2. Bajtové rozložení ratchet blobu (major 4)

```
Fáze 2 (jen symetrický ratchet, bez KEM):
  [1B major=4]
  [1B htype]            // příznaky hlavičky; bit0 = přítomen KEM oddíl (Fáze 4)
  [4B epoch BE]         // e_send: adresovací epocha (NE den)
  [4B msgNo BE]         // n_send: globální monotónní pořadí ve směru
  [ciphertext + 16B GCM tag]

Fáze 4 (KEM krok — jen na zprávách, co posouvají kořen; htype bit0 = 1):
  [1B major=4][1B htype][4B epoch][4B msgNo]
  [2B pubLen BE][pub]   // čerstvý ML-KEM veřejný klíč odesílatele
  [2B ctLen BE][ct]     // ML-KEM ciphertext k poslednímu cizímu pubkey
  [ciphertext + 16B GCM tag]
```

- **IV se NEpřenáší.** Odvozuje se z klíče zprávy (§4), takže v blobu není.
  Bezpečné: klíč zprávy je unikátní per (směr, msgNo), tedy i (aesKey, iv) je
  unikátní → GCM neopakuje (key, IV). **Invariant odesílatele:** jeden `msgNo`
  = právě jeden plaintext (retransmise TÉŽE zprávy je bajtově identická; jiný
  obsah pod stejným msgNo se NIKDY neposílá).
- KEM oddíl (pub/ct) je **čitelný před dešifrováním** — musí být, protože se z něj
  odvozuje klíč, kterým se blob dešifruje (viz Signal: ratchet pubkey v hlavičce).
- Vnitřní **plaintext (pod GCM) = beze změny reuse legacy formátu**:
  `[1B kind][1B minor][8B ts BE][4B len BE][data][trailer?][(u textu) výplň]`.
  Tzn. `kind` 0–3, TLV trailer, inzerce capability/maxMajor, reakce, přenos
  souborů, padding do košů — všechno funguje identicky. Ratchet mění JEN vnější
  obálku, adresu a klíč; vnitřek se parsuje stávajícím `parsePayload`.

---

## 3. AAD (autentizace hlavičky)

Vážou se **všechna čitelná pole** naráz + směr:

```
aad = ("ccr|dir=" + dir)  ++  headerBytes
headerBytes = blob[0 .. začátek ciphertextu)   // major, htype, epoch, msgNo, [KEM oddíl]
```

Tím je zapečený major, htype, epocha, pořadí i KEM materiál — relay nemůže nic
z toho přehodit ani podvrhnout (rozbil by GCM tag). Směr `dir` v labelu brání
záměně odchozí/příchozí schránky (stejně jako legacy `ccdir:`). Legacy AAD
(`ccdir:$dir|w:$wire`) se NEMĚNÍ.

---

## 4. KDF labely a odvození klíčů

```
# Bootstrap kořene ze statického klíče kontaktu M (= Contact.keyBase64)
RK_0        = HKDF(ikm=decode(M), info="CryptoChat/ratchet/root-init/v1", 32)

# Iniciální řetěze z kořene (per směr). sendDir/recvDir jako dnes dle Contact.initiator.
# Můj send řetěz = protějškův recv řetěz (symetrické labely → obě strany shodně).
CK_send_0   = HKDF(RK, info="CryptoChat/ratchet/chain/v1|dir=<sendDir>", 32)
CK_recv_0   = HKDF(RK, info="CryptoChat/ratchet/chain/v1|dir=<recvDir>", 32)

# Symetrický krok řetězu (z CK_n dvě nezávislé hodnoty)
MK_n        = HKDF(ikm=CK_n, info="CryptoChat/ratchet/mk/v1", 32)
CK_{n+1}    = HKDF(ikm=CK_n, info="CryptoChat/ratchet/ck/v1", 32)
# Po výpočtu zahoď CK_n (forward secrecy). MK_n použij jednou a zahoď
# (nebo ulož do skipped-keys při out-of-order příjmu).

# AEAD materiál z klíče zprávy (klíč + IV, žádný IV se neposílá)
keyIv       = HKDF(ikm=MK_n, info="CryptoChat/ratchet/aead/v1", 44)
aesKey      = keyIv[0..32)   ; iv = keyIv[32..44)
```

**Fáze 4 (KEM krok):** z ML-KEM tajemství `ss` a starého kořene se odvodí nový
kořen a přeseje řetěz:
```
RK'         = HKDF(ikm=(RK ++ ss), info="CryptoChat/ratchet/rekey-root/v1|gen=<g>", 32)  // realita, viz DoubleRatchet.REKEY_ROOT
CK_recv'_0  = HKDF(ikm=RK', info="CryptoChat/ratchet/chain/v1|dir=<recvDir>|gen=<g>", 32)
```
`gen` = pořadí KEM kroku (aby se řetěze různých generací nepotkaly). SAS kód pro
volitelné ověření: `HKDF(ikm=ss, info="CryptoChat/ratchet/sas/v1", 6)`. (Detail
Fáze 4; sem patří pro úplnost.)

---

## 5. Adresy schránek a beacon (bez hodin, Návrh 2)

Odvozeno z **neměnného `M`** (přijatý kompromis: adresy nejsou forward-secret
vůči pozdější kompromitaci zařízení; ratchetovaný seed = pozdější hardening).

```
mailbox_seed = HKDF(ikm=decode(M), info="CryptoChat/relay/ratchet-mailbox/v1", 32)
mailbox(dir,e) = b64url( HKDF(ikm=mailbox_seed, info="dir=<dir>|epoch=<e>", 24) )
beacon(dir)    = b64url( HKDF(ikm=mailbox_seed, info="beacon|dir=<dir>", 24) )
```

- 24 B → base64url bez výplně (splňuje regex ID na serveru, stejně jako legacy).
- `epoch` = **ratchet epocha** (u32 čítač), NE den. Label je odlišný od legacy
  `INFO_MAILBOX`, takže ratchet a legacy schránky nikdy nekolidují.
- **Směr:** odchozí na `mailbox(sendDir, e_send)`; příjem pollne
  `mailbox(recvDir, e_recv .. e_recv+W)` + `beacon(recvDir)`.

**Beacon pointer** (Fáze 3b, discovery po dlouhém offline): odesílatel při posunu
epochy zapíše do `beacon(dir)` šifrovaný ukazatel aktuální epochy. Zapisuje se
**jednou za epochu** (spolehlivě přes `pointerMarker` = poslední úspěšně zapsaná
epocha; dokud `put` neprojde, zkusí to každé další odeslání). Příjemce beacon čte
**jen když sousední epocha `re+1` nic nepřinesla** (za běžného provozu se neplatí),
bere **max** ze všech blobů a **neackuje** (aby o ukazatel nepřišel při selhání
následného fetche; uklidí ho TTL).
```
beacon_key  = HKDF(ikm=decode(M), info="CryptoChat/ratchet/beacon-key/v1", 32)
payload     = [4B epoch BE]            # AES-256-GCM, aad = "ccrb|dir=<dir>"
blob        = IV[12] ++ ciphertext ++ tag[16]
```

---

## 6. Pravidla epochy a pořadí (Návrh 2)

- `msgNo` je **globální monotónní** čítač ve směru (neresetuje se na hranici
  epochy). Indexuje pozici v symetrickém řetězu.
- `epoch` (e_send) se posune, když `msgsSinceEpoch` dosáhne `K`
  (`RATCHET_EPOCH_MSGS`). Při posunu `epoch++`, `msgsSinceEpoch = 0`. `msgNo` běží
  dál (řetěz se posunem epochy NEpřeseje — to dělá jen KEM krok). **Pozn.:** KEM
  krok (Fáze 4) epochu NEposouvá (viz `applyRekey`); dřívější návrh „(a) KEM krok
  posune epochu" se do implementace nepromítl (nález v2.0-34 / A-N2).
- **Příjem:** pollni `mailbox(recvDir, e_recv .. e_recv+W)`. Ve zprávě přečti
  autentizovaný `epoch`,`msgNo`. Posuň `e_recv` na vyšší viděnou epochu. Pro
  `msgNo > n_recv` přetoč řetěz o `msgNo − n_recv` kroků (max `RATCHET_SKIP_MAX`,
  jinak → karanténa), přeskočené `MK` ulož. Pro `msgNo ≤ n_recv` (out-of-order /
  už viděné) najdi `MK` ve skipped-store (idempotence příjmu).
  **Okno `e_recv .. e_recv+W` je DURABILNÍ „podlaha" (`RatchetState.backfillFloor`),
  ne jen aktuální `e_recv`:** dešifrování vyšší epochy posune `e_recv` hned, ale
  schránky nižších epoch se musí teprve dočíst — podlaha se posune AŽ po jejich
  prokazatelném vyprázdnění, takže přechodné selhání GETu epochu neztratí
  (nález v2.0-27 + reziduum v2.0-29).
- **Discovery zaostalého příjemce** (utekl za `W`): dorovnej z `beacon`; když je
  prázdný, **odešli první** (vlastní `e_send` znáš vždy) — odpověď protějšku
  přinese jeho aktuální epochu.

---

## 7. Co je zmražené / golden-testované

- Legacy major 3: bajtově NEMĚNIT (`ChatEnvelopeGoldenTest`, `LegacyWireCompatTest`).
- Nové golden vzorky (nikdy neupravovat po zavedení):
  - ratchet blob major 4 bez KEM (Fáze 2),
  - ratchet blob s KEM oddílem (Fáze 4),
  - beacon pointer blob (Fáze 3).
- Registr `WireExt` (typy TLV, feature id, bity schopností) se NErecykluje.
  Nový capability bit pro rotaci NENÍ pro korektnost potřeba — gate je
  `peerCanReadMajor(4)`. (Volitelně lze přidat `CAP_ROTATION` čistě pro UI.)

---

## 8. Parametry k potvrzení

- `RATCHET_EPOCH_MSGS` K = 32 (jak často rotovat schránku jednosměrně).
- `RATCHET_LOOKAHEAD` W = 8 (kolik schránek dopředu pollovat).
- `RATCHET_SKIP_MAX` = 1000 (strop přeskočení / přetočení řetězu).
