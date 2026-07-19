#!/usr/bin/env bash
# Nastaví auto-deploy relaye: když se do ~/cryptochat-relay/server.py nahraje nová
# verze (scp), systemd ji zvaliduje (kontrola syntaxe), nasadí do /opt a restartuje
# službu. Relay tak jde aktualizovat POUHÝM scp - bez sudo.
#
# Spustit JEDNOU jako root:  sudo bash ~/cryptochat-relay/setup-autodeploy.sh
#
# Bezpečnost: nic nevystavuje do sítě; využít to může jen ten, kdo už má SSH k
# uživateli jelenxp. Nasazený kód běží pod neprivilegovaným 'cryptochat', ne root.
# Deploy skript je root-owned (jelenxp ho nemůže změnit).
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Spusť jako root: sudo bash $0"; exit 1; }

DEPLOY_USER="${SUDO_USER:-jelenxp}"
HOME_DIR="$(eval echo "~$DEPLOY_USER")"
SRC="$HOME_DIR/cryptochat-relay/server.py"

install -d /opt/cryptochat-relay

# --- deploy.sh (root-owned, spouští ho systemd při změně zdroje) ---
cat >/opt/cryptochat-relay/deploy.sh <<EOF
#!/usr/bin/env bash
set -uo pipefail
SRC="$SRC"
DST=/opt/cryptochat-relay/server.py
LOG=/opt/cryptochat-relay/deploy.log
{
  echo "[\$(date '+%F %T')] zmena zdroje, validuji..."
  if [ ! -f "\$SRC" ]; then echo "  zdroj chybi, koncim"; exit 0; fi
  if ! python3 -m py_compile "\$SRC" 2>/tmp/cc_deploy_err; then
    echo "  SYNTAX ERROR - NEnasazuji, ponechavam starou verzi:"
    sed 's/^/    /' /tmp/cc_deploy_err
    exit 0
  fi
  install -m 644 -o cryptochat -g cryptochat "\$SRC" "\$DST"
  systemctl restart cryptochat-relay
  sleep 1
  echo "  nasazeno OK, is-active: \$(systemctl is-active cryptochat-relay)"
} >>"\$LOG" 2>&1
EOF
chown root:root /opt/cryptochat-relay/deploy.sh
chmod 755 /opt/cryptochat-relay/deploy.sh
touch /opt/cryptochat-relay/deploy.log
chmod 644 /opt/cryptochat-relay/deploy.log

# --- systemd: oneshot služba, kterou spustí path unit ---
cat >/etc/systemd/system/cryptochat-deploy.service <<'EOF'
[Unit]
Description=Deploy CryptoChat relay on source change

[Service]
Type=oneshot
ExecStart=/opt/cryptochat-relay/deploy.sh
EOF

# --- systemd: path unit hlídající zdrojový soubor (spustí se po zavření zápisu) ---
cat >/etc/systemd/system/cryptochat-deploy.path <<EOF
[Unit]
Description=Watch CryptoChat relay source for changes

[Path]
PathChanged=$SRC
Unit=cryptochat-deploy.service

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now cryptochat-deploy.path

echo "Auto-deploy nastaven. Hlídá: $SRC"
echo "Update relaye = scp nove server.py do ~/cryptochat-relay/ (zbytek automaticky)."
