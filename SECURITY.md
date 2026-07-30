# Security Policy

**English** · [Čeština](#bezpečnostní-politika)

CryptoChat is a privacy-focused messenger, so security reports are taken
seriously. Thank you for helping keep it safe.

## Reporting a vulnerability

**Please do not open a public issue for security problems** — that would expose
the flaw before it can be fixed.

Instead, use GitHub's **private vulnerability reporting**:

1. Open the repository's **Security** tab, then **Advisories** in the left
   sidebar — or go straight to
   <https://github.com/JelenXP/CryptoChat/security/advisories>.
2. Click the green **Report a vulnerability** button.
3. Describe what you found: how to reproduce it, the impact, and the affected
   version.

The report reaches the maintainer privately — nobody else can see it.

If you can't use that form for any reason, open a normal issue that says only
that you'd like a private security contact — **with no details of the
vulnerability** — and you'll be pointed to a private channel.

## What to expect

This is a small, personal open-source project maintained in spare time, so
responses are best-effort:

- Acknowledgement: usually within a few days.
- Please practice **coordinated disclosure** — give a reasonable chance to
  ship a fix before you disclose publicly.

## Supported versions

Only the **latest release** receives security fixes. Both sides of a
conversation must run compatible versions and the app updates itself, so always
test against the newest release.

## Already-known limitations (not vulnerabilities)

Some properties are documented trade-offs, not bugs — please check before
reporting:

- The relay can tell that *some* mailbox received *something* at a given time
  (there is no cover traffic).
- A global passive adversary who can watch both ends at once can attempt timing
  correlation. Tor makes this harder, not impossible.
- A compromised device exposes its own keys.

These and the full threat model are described in [README.md](README.md) and
[SERVER.md](SERVER.md).

---
---

# Bezpečnostní politika

[English](#security-policy) · **Čeština**

CryptoChat je messenger zaměřený na soukromí, takže bezpečnostní hlášení bereme
vážně. Díky, že pomáháš držet ho v bezpečí.

## Jak nahlásit zranitelnost

**Nezakládej prosím veřejný issue** pro bezpečnostní problémy — odhalilo by to
chybu dřív, než ji jde opravit.

Použij místo toho **soukromé nahlašování zranitelností** na GitHubu:

1. Otevři kartu **Security** repozitáře a v levém menu **Advisories** — nebo rovnou
   <https://github.com/JelenXP/CryptoChat/security/advisories>.
2. Klikni na zelené tlačítko **Report a vulnerability**.
3. Popiš, co jsi našel: jak to reprodukovat, jaký to má dopad a které verze se
   to týká.

Hlášení dorazí správci soukromě — nikdo jiný ho nevidí.

Kdybys ten formulář z jakéhokoli důvodu použít nemohl, založ běžný issue jen se
žádostí o soukromý bezpečnostní kontakt — **bez jakýchkoli detailů
zranitelnosti** — a dostaneš odkaz na soukromý kanál.

## Co očekávat

Tohle je malý osobní open-source projekt udržovaný ve volném čase, takže reakce
jsou v rámci možností:

- Potvrzení příjmu: obvykle do několika dní.
- Drž se prosím **koordinovaného zveřejnění** — dej rozumný čas na vydání opravy,
  než chybu zveřejníš.

## Podporované verze

Bezpečnostní opravy dostává jen **poslední vydání**. Obě strany konverzace musí
běžet na kompatibilních verzích a appka se aktualizuje sama, takže testuj vždy
proti nejnovějšímu vydání.

## Už známá omezení (nejsou to zranitelnosti)

Některé vlastnosti jsou zdokumentované kompromisy, ne chyby — mrkni na ně, než
budeš hlásit:

- Relay pozná, že *nějaká* schránka v nějaký čas *něco* dostala (není žádný
  cover traffic).
- Globální pasivní odposlech, který vidí obě strany zároveň, může zkoušet
  časovou korelaci. Tor to ztěžuje, ne však dokonale.
- Kompromitované zařízení vydá svoje vlastní klíče.

Tato i celý threat model jsou popsané v [README.md](README.md) a
[SERVER.md](SERVER.md).
