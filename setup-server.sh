#!/usr/bin/env bash
# Nastaví CryptoChat relay jako službu + Tor onion službu. Kroky vyžadují root.
# Spusť jednou:  sudo bash ~/cryptochat-relay/setup-server.sh
#
# Co to udělá:
#   1) nainstaluje Tor
#   2) nasadí relay (server.py) jako systemd službu běžící pod uživatelem 'cryptochat'
#      na 127.0.0.1:8787 (nevystavený do sítě - ven jde jen přes Tor)
#   3) zapne onion službu (port 80 -> 127.0.0.1:8787) a vypíše .onion adresu
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Spusť jako root:  sudo bash $0"
    exit 1
fi

USER_HOME=$(eval echo "~${SUDO_USER:-$USER}")
SRC="$USER_HOME/cryptochat-relay/server.py"
PY=$(command -v python3)

if [ ! -f "$SRC" ]; then
    echo "Nenašel jsem $SRC - nejdřív nahraj server.py do ~/cryptochat-relay/"
    exit 1
fi

echo "== 1/4 Instalace Toru =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq tor

echo "== 2/4 Relay jako systemd služba =="
install -d /opt/cryptochat-relay
install -m 644 "$SRC" /opt/cryptochat-relay/server.py
id cryptochat >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin cryptochat
# Uvolni port po případném testovacím běhu.
pkill -f "cryptochat-relay/server.py" 2>/dev/null || true
cat >/etc/systemd/system/cryptochat-relay.service <<EOF
[Unit]
Description=CryptoChat zero-knowledge relay
After=network.target

[Service]
Type=simple
User=cryptochat
WorkingDirectory=/opt/cryptochat-relay
ExecStart=$PY /opt/cryptochat-relay/server.py
Restart=on-failure
RestartSec=3
Environment=CC_HOST=127.0.0.1
Environment=CC_PORT=8787
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now cryptochat-relay

echo "== 3/4 Onion služba v Toru =="
if ! grep -q "^HiddenServiceDir /var/lib/tor/cryptochat/" /etc/tor/torrc; then
cat >>/etc/tor/torrc <<'EOF'

# CryptoChat onion service
HiddenServiceDir /var/lib/tor/cryptochat/
HiddenServicePort 80 127.0.0.1:8787
EOF
fi
systemctl restart tor
echo "čekám na vygenerování onion adresy..."
for i in $(seq 1 20); do [ -f /var/lib/tor/cryptochat/hostname ] && break; sleep 1; done

echo "== 4/4 Hotovo =="
echo -n "Relay health: "; curl -s http://127.0.0.1:8787/health || echo "NEODPOVÍDÁ"
echo ""
echo "=================================================="
echo "ONION ADRESA (zadej do appky jako  http://<adresa> ):"
cat /var/lib/tor/cryptochat/hostname 2>/dev/null || echo "(hostname ještě není - zkus: sudo cat /var/lib/tor/cryptochat/hostname)"
echo "=================================================="
